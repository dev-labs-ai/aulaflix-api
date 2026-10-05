package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.CourseFaqEntry;
import com.devlabs.aulaflix.dto.AdminCourse;
import com.devlabs.aulaflix.dto.AdminCourseList;
import com.devlabs.aulaflix.dto.CourseDocument;
import com.devlabs.aulaflix.dto.CourseStatusChange;
import com.devlabs.aulaflix.dto.FaqEntry;
import com.devlabs.aulaflix.dto.NewCourseRequest;
import com.devlabs.aulaflix.dto.Readiness;
import com.devlabs.aulaflix.exception.CourseCannotMoveBackException;
import com.devlabs.aulaflix.exception.CourseNotDraftException;
import com.devlabs.aulaflix.exception.CourseNotFoundException;
import com.devlabs.aulaflix.exception.CourseRequirementsUnmetException;
import com.devlabs.aulaflix.exception.FreeLessonIneligibleException;
import com.devlabs.aulaflix.exception.PriceNotDivisibleByInstallmentsException;
import com.devlabs.aulaflix.exception.SlugFrozenException;
import com.devlabs.aulaflix.exception.SlugTakenException;
import com.devlabs.aulaflix.repository.CourseRepository;
import com.devlabs.aulaflix.repository.LessonRepository;
import com.devlabs.aulaflix.repository.WaitlistEntryRepository;
import com.devlabs.aulaflix.repository.WaitlistEntryRepository.WaitlistCount;

/**
 * The catalog's Courses as Admins author them. Every change holds the Course's row lock, so that an edit, a move and a
 * deletion of one Course go one at a time: a move could otherwise check a document that a concurrent edit is emptying.
 */
@Service
public class CourseService {

    private static final Logger log = LoggerFactory.getLogger(CourseService.class);

    private final CourseRepository repository;
    private final LessonRepository lessons;
    private final WaitlistEntryRepository waitlistEntries;
    private final CatalogLocks locks;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public CourseService(CourseRepository repository, LessonRepository lessons,
                         WaitlistEntryRepository waitlistEntries, CatalogLocks locks, ApplicationEventPublisher events,
                         Clock clock) {
        this.repository = repository;
        this.lessons = lessons;
        this.waitlistEntries = waitlistEntries;
        this.locks = locks;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public AdminCourse create(long adminId, NewCourseRequest request) {
        if (repository.existsBySlug(request.slug())) {
            throw new SlugTakenException();
        }
        CourseEntity course = repository.save(new CourseEntity(request.slug(), request.title()));
        log.info("Admin {} created Course {}", adminId, course.getId());
        return adminView(course);
    }

    /** Unpaginated: one person authors the catalog, a few dozen Courses at most. Every Waitlist is counted at once. */
    @Transactional(readOnly = true)
    public AdminCourseList list() {
        Map<Long, Long> waitlistCounts = waitlistEntries.countByCourse().stream()
                .collect(Collectors.toMap(WaitlistCount::getCourseId, WaitlistCount::getEntries));
        return new AdminCourseList(repository.findAll(Sort.by("id")).stream()
                .map(course -> adminView(course, waitlistCounts.getOrDefault(course.getId(), 0L)))
                .toList());
    }

    @Transactional(readOnly = true)
    public AdminCourse get(String courseId) {
        return adminView(find(courseId));
    }

    /**
     * Replaces the whole editable document: a field left out is cleared. A Course that is no longer a Draft keeps its
     * slug, and every field its state needs. Those are checked on the Course as the document leaves it, and refusing
     * rolls the document back. The Free lesson is a published Lesson of the Course in every state, so that it already
     * is one when the Course goes On sale.
     */
    @Transactional
    public AdminCourse update(long adminId, String courseId, CourseDocument document) {
        CourseEntity course = locks.course(courseId);
        requireSlugChangeAllowed(course, document.slug());
        requireExactInstallments(document);
        requirePublishedLessonOf(course, document.freeLessonId());
        apply(document, course);
        requireFitFor(course.getStatus(), course);
        log.info("Admin {} updated Course {}", adminId, course.getId());
        return adminView(course);
    }

    /**
     * Sending the state the Course is already in changes nothing, so a retried move is harmless. The instant is cut to
     * the microseconds PostgreSQL keeps, so that the answer shows what every later read will.
     */
    @Transactional
    public AdminCourse changeStatus(long adminId, String courseId, CourseStatusChange change) {
        CourseEntity course = locks.course(courseId);
        if (change.status().compareTo(course.getStatus()) < 0) {
            throw new CourseCannotMoveBackException();
        }
        if (change.status() == course.getStatus()) {
            return adminView(course);
        }
        requireFitFor(change.status(), course);
        course.moveTo(change.status(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        log.info("Admin {} moved Course {} to {}", adminId, course.getId(), change.status());
        return adminView(course);
    }

    /** Only a Draft, which no one but Admins has ever seen; its Lessons' videos go once the deletion commits. */
    @Transactional
    public void delete(long adminId, String courseId) {
        CourseEntity course = locks.course(courseId);
        if (course.getStatus() != CourseStatus.DRAFT) {
            throw new CourseNotDraftException();
        }
        List<Long> lessonIds = lessons.findIdsByCourseId(course.getId());
        repository.delete(course);
        events.publishEvent(new LessonsDeleted(lessonIds));
        log.info("Admin {} deleted Course {}", adminId, course.getId());
    }

    /** Takes the id as the path carries it, so that an id of any shape answers like an unknown one. */
    private CourseEntity find(String courseId) {
        return PathIds.parse(courseId).flatMap(repository::findById).orElseThrow(CourseNotFoundException::new);
    }

    /** The slug is the web's address for the Course, so it stays put once anyone but Admins can see it. */
    private void requireSlugChangeAllowed(CourseEntity course, String slug) {
        if (course.getSlug().equals(slug)) {
            return;
        }
        if (course.getStatus() != CourseStatus.DRAFT) {
            throw new SlugFrozenException();
        }
        if (repository.existsBySlug(slug)) {
            throw new SlugTakenException();
        }
    }

    private static void requireFitFor(CourseStatus state, CourseEntity course) {
        List<String> missing = CourseRequirements.missingFor(state, course);
        if (!missing.isEmpty()) {
            throw new CourseRequirementsUnmetException(missing);
        }
    }

    /** The installment is {@code priceCents / maxInstallments} with no remainder, once both are set. */
    private static void requireExactInstallments(CourseDocument document) {
        if (document.priceCents() != null && document.maxInstallments() != null
                && document.priceCents() % document.maxInstallments() != 0) {
            throw new PriceNotDivisibleByInstallmentsException();
        }
    }

    /** Under the Course's lock, so no Lesson can move in or out of it meanwhile; a published Lesson stays so. */
    private void requirePublishedLessonOf(CourseEntity course, Long freeLessonId) {
        if (freeLessonId != null && !lessons.isPublishedLessonOf(freeLessonId, course.getId())) {
            throw new FreeLessonIneligibleException();
        }
    }

    private static void apply(CourseDocument document, CourseEntity course) {
        course.setSlug(document.slug());
        course.setTitle(document.title());
        course.setSummary(document.summary());
        course.setArea(document.area());
        course.setIcon(document.icon());
        course.setTone(document.tone());
        course.setAbout(orEmpty(document.about()));
        course.setLearn(orEmpty(document.learn()));
        course.setAudience(orEmpty(document.audience()));
        course.setPlannedTopics(orEmpty(document.plannedTopics()));
        course.setFaq(orEmpty(document.faq()).stream()
                .map(entry -> new CourseFaqEntry(entry.question(), entry.answer()))
                .toList());
        course.setPriceCents(document.priceCents());
        course.setPixDiscountPercent(document.pixDiscountPercent());
        course.setMaxInstallments(document.maxInstallments());
        course.setFreeLessonId(document.freeLessonId());
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private AdminCourse adminView(CourseEntity course) {
        return adminView(course, course.getStatus() == CourseStatus.COMING_SOON
                ? waitlistEntries.countByCourseId(course.getId())
                : 0L);
    }

    /** The Waitlist's count shows only while the Course is Coming soon, the one state that takes entries. */
    private static AdminCourse adminView(CourseEntity course, long waitlistCount) {
        return new AdminCourse(
                course.getId(),
                course.getSlug(),
                course.getTitle(),
                course.getSummary(),
                course.getArea(),
                course.getIcon(),
                course.getTone(),
                course.getAbout(),
                course.getLearn(),
                course.getAudience(),
                course.getPlannedTopics(),
                course.getFaq().stream().map(CourseService::faqEntry).toList(),
                course.getPriceCents(),
                course.getPixDiscountPercent(),
                course.getMaxInstallments(),
                course.getFreeLessonId(),
                course.getStatus(),
                readinessOf(course),
                course.getStatus() == CourseStatus.COMING_SOON ? waitlistCount : null,
                course.getComingSoonAt(),
                course.getOnSaleAt());
    }

    /** Only the states the Course can still move to, and none at all once it is On sale. */
    private static Readiness readinessOf(CourseEntity course) {
        return switch (course.getStatus()) {
            case DRAFT -> new Readiness(CourseRequirements.missingFor(CourseStatus.COMING_SOON, course),
                    CourseRequirements.missingFor(CourseStatus.ON_SALE, course));
            case COMING_SOON -> new Readiness(null, CourseRequirements.missingFor(CourseStatus.ON_SALE, course));
            case ON_SALE -> null;
        };
    }

    private static FaqEntry faqEntry(CourseFaqEntry entry) {
        return new FaqEntry(entry.question(), entry.answer());
    }
}
