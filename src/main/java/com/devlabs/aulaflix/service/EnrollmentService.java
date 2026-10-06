package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.EnrollmentEndReason;
import com.devlabs.aulaflix.domain.EnrollmentOrigin;
import com.devlabs.aulaflix.domain.EnrollmentStatus;
import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.EnrollmentEntity;
import com.devlabs.aulaflix.domain.entity.OrderEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.dto.AccountSummary;
import com.devlabs.aulaflix.dto.AdminEnrollment;
import com.devlabs.aulaflix.dto.CourseSummary;
import com.devlabs.aulaflix.dto.EnrollmentStatusChange;
import com.devlabs.aulaflix.dto.ManualEnrollmentRequest;
import com.devlabs.aulaflix.dto.PageResponse;
import com.devlabs.aulaflix.exception.AlreadyEnrolledException;
import com.devlabs.aulaflix.exception.CourseNotEnrollableException;
import com.devlabs.aulaflix.exception.EnrollmentNotFoundException;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;
import com.devlabs.aulaflix.exception.PaidEnrollmentException;
import com.devlabs.aulaflix.exception.StudentAccountRequiredException;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.CourseRepository;
import com.devlabs.aulaflix.repository.EnrollmentRepository;

/**
 * The Enrollments module: a Student's right to watch a Course, granted with an origin and ended with a reason. A
 * Student has at most one active Enrollment per Course; one that ended stays, and access comes back only through a new
 * one. Every grant holds the Student's row lock, so that two grants of one Student go one at a time and the second sees
 * the first.
 */
@Service
public class EnrollmentService {

    private static final Logger log = LoggerFactory.getLogger(EnrollmentService.class);

    private final EnrollmentRepository repository;
    private final AccountRepository accounts;
    private final CourseRepository courses;
    private final Clock clock;

    public EnrollmentService(EnrollmentRepository repository, AccountRepository accounts, CourseRepository courses,
                             Clock clock) {
        this.repository = repository;
        this.accounts = accounts;
        this.courses = courses;
        this.clock = clock;
    }

    /** Whether the Student may watch the Course's Lessons now. */
    @Transactional(readOnly = true)
    public boolean isActivelyEnrolled(long studentId, long courseId) {
        return repository.existsByStudentIdAndCourseIdAndEndedAtIsNull(studentId, courseId);
    }

    /** The Student's active Enrollments, each with its Course, oldest first: what "Meus cursos" starts from. */
    @Transactional(readOnly = true)
    public List<EnrollmentEntity> activeEnrollmentsOf(long studentId) {
        return repository.findActiveWithCourseByStudentId(studentId);
    }

    /** The Student's active Enrollment in the Course, with the Course, if there is one. */
    @Transactional(readOnly = true)
    public Optional<EnrollmentEntity> activeEnrollment(long studentId, long courseId) {
        return repository.findActiveWithCourse(studentId, courseId);
    }

    /**
     * Grants an Enrollment by hand to the Student with the email, in a Coming soon or On sale Course, with the note that
     * is the only record of why. It sends no email: the Admin tells the Student. A Course id of any shape answers like
     * an unknown one.
     */
    @Transactional
    public AdminEnrollment grantManually(long adminId, ManualEnrollmentRequest request) {
        String note = EnrollmentNotes.trimmed(request.note());
        AccountEntity student = accounts
                .findLockedByEmailAndRole(AccountInputRules.normalizeEmail(request.email()), Role.STUDENT)
                .orElseThrow(StudentAccountRequiredException::new);
        CourseEntity course = PathIds.parse(request.courseId()).flatMap(courses::findById)
                .filter(found -> found.getStatus() != CourseStatus.DRAFT)
                .orElseThrow(CourseNotEnrollableException::new);
        AccountEntity admin = accounts.findById(adminId).orElseThrow();
        EnrollmentEntity enrollment = start(EnrollmentEntity.grantedManually(student, course, now(), admin, note));
        log.info("Admin {} granted Enrollment {} to Student {} in Course {}", adminId, enrollment.getId(),
                student.getId(), course.getId());
        return adminView(enrollment);
    }

    /**
     * Grants the Enrollment that the paid Order buys, within the caller's transaction, which holds the Student's lock.
     * The caller has made sure the Student has no active Enrollment in the Course.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantForOrder(OrderEntity order) {
        EnrollmentEntity enrollment = start(EnrollmentEntity.grantedByOrder(order, now()));
        log.info("Order {} granted Enrollment {} to Student {} in Course {}", order.getCode(), enrollment.getId(),
                order.getStudent().getId(), order.getCourse().getId());
    }

    /**
     * Ends, with the reason, the Enrollment the Order's payment granted, if it is still active, within the caller's
     * transaction, which holds the Student's lock. A Duplicate payment granted none, so the Enrollment the Student holds
     * through another Order, or by hand, stays.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void endGrantedBy(OrderEntity order, EnrollmentEndReason reason) {
        repository.findByOrderId(order.getId()).filter(EnrollmentEntity::isActive).ifPresent(enrollment -> {
            enrollment.endWithItsOrder(now(), reason);
            log.info("Order {} ended Enrollment {} with {}", order.getCode(), enrollment.getId(), reason);
        });
    }

    /**
     * Newest first. Each filter is optional: the Student's email, matched trimmed and lower-cased; the Course's id, of
     * any shape, which matches nothing unless some Course could have it; and whether the Enrollment is active,
     * {@code true} or {@code false}. Only the page and its size are taken from the request: the order is fixed.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminEnrollment> list(String email, String courseId, String active, Pageable pageable) {
        Boolean onlyActive = parseActive(active);
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Optional<Long> course = Optional.ofNullable(courseId).flatMap(PathIds::parse);
        if (courseId != null && course.isEmpty()) {
            return pageOf(Page.empty(page));
        }
        String student = email == null ? null : AccountInputRules.normalizeEmail(email);
        return pageOf(repository.search(student, course.orElse(null), onlyActive, page));
    }

    /** Takes the id as the path carries it, so that an id of any shape answers like an unknown one. */
    @Transactional(readOnly = true)
    public AdminEnrollment get(String enrollmentId) {
        return PathIds.parse(enrollmentId).flatMap(repository::findWithPartiesById)
                .map(EnrollmentService::adminView)
                .orElseThrow(EnrollmentNotFoundException::new);
    }

    /**
     * Ends a manual Enrollment by hand, with a note: {@code ENDED} is the only status taken. The ending is final, and
     * ending an ended Enrollment changes nothing, so a retried ending is harmless and keeps the first one's note. One an
     * Order granted ends only with its Order, through a Refund or a Reversal, and is refused whatever its state. The
     * Enrollment's lock makes two endings go one at a time.
     */
    @Transactional
    public AdminEnrollment changeStatus(long adminId, String enrollmentId, EnrollmentStatusChange change) {
        if (change.status() != EnrollmentStatus.ENDED) {
            throw new InvalidRequestException(List.of(new FieldViolation("status", "invalid-format")));
        }
        String note = EnrollmentNotes.trimmed(change.note());
        EnrollmentEntity enrollment = PathIds.parse(enrollmentId).flatMap(repository::findLockedById)
                .orElseThrow(EnrollmentNotFoundException::new);
        if (enrollment.getOrigin() == EnrollmentOrigin.ORDER) {
            throw new PaidEnrollmentException();
        }
        if (!enrollment.isActive()) {
            return adminView(enrollment);
        }
        enrollment.endManually(now(), accounts.findById(adminId).orElseThrow(), note);
        log.info("Admin {} ended Enrollment {}", adminId, enrollment.getId());
        return adminView(enrollment);
    }

    /** Under the Student's lock, which the caller holds. */
    private EnrollmentEntity start(EnrollmentEntity enrollment) {
        if (isActivelyEnrolled(enrollment.getStudent().getId(), enrollment.getCourse().getId())) {
            throw new AlreadyEnrolledException();
        }
        return repository.save(enrollment);
    }

    /** Cut to the microseconds PostgreSQL keeps, so that an answer shows what every later read will. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Only {@code true} or {@code false}, or no filter at all. */
    private static Boolean parseActive(String active) {
        if (active == null) {
            return null;
        }
        return switch (active) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new InvalidRequestException(List.of(new FieldViolation("active", "invalid-format")));
        };
    }

    private static PageResponse<AdminEnrollment> pageOf(Page<EnrollmentEntity> found) {
        return new PageResponse<>(found.map(EnrollmentService::adminView).getContent(), found.getNumber(),
                found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    private static AdminEnrollment adminView(EnrollmentEntity enrollment) {
        CourseEntity course = enrollment.getCourse();
        return new AdminEnrollment(
                enrollment.getId(),
                statusOf(enrollment),
                summaryOf(enrollment.getStudent()),
                new CourseSummary(course.getId(), course.getSlug(), course.getTitle(), course.getStatus()),
                enrollment.getStartedAt(),
                enrollment.getOrigin(),
                enrollment.getOrder() == null ? null : enrollment.getOrder().getCode(),
                summaryOf(enrollment.getGrantedBy()),
                enrollment.getGrantNote(),
                enrollment.getEndedAt(),
                enrollment.getEndReason(),
                summaryOf(enrollment.getEndedBy()),
                enrollment.getEndNote());
    }

    private static EnrollmentStatus statusOf(EnrollmentEntity enrollment) {
        return enrollment.isActive() ? EnrollmentStatus.ACTIVE : EnrollmentStatus.ENDED;
    }

    private static AccountSummary summaryOf(AccountEntity account) {
        return account == null ? null : new AccountSummary(account.getId(), account.getEmail(), account.getName());
    }
}
