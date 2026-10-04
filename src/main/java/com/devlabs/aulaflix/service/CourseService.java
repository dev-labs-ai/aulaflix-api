package com.devlabs.aulaflix.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.CourseFaqEntry;
import com.devlabs.aulaflix.dto.AdminCourse;
import com.devlabs.aulaflix.dto.AdminCourseList;
import com.devlabs.aulaflix.dto.CourseDocument;
import com.devlabs.aulaflix.dto.FaqEntry;
import com.devlabs.aulaflix.dto.NewCourseRequest;
import com.devlabs.aulaflix.dto.Readiness;
import com.devlabs.aulaflix.exception.CourseNotDraftException;
import com.devlabs.aulaflix.exception.CourseNotFoundException;
import com.devlabs.aulaflix.exception.PriceNotDivisibleByInstallmentsException;
import com.devlabs.aulaflix.exception.SlugTakenException;
import com.devlabs.aulaflix.repository.CourseRepository;

/** The catalog's Courses as Admins author them. */
@Service
public class CourseService {

    private static final Logger log = LoggerFactory.getLogger(CourseService.class);

    private final CourseRepository repository;

    public CourseService(CourseRepository repository) {
        this.repository = repository;
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

    /** Unpaginated: one person authors the catalog, a few dozen Courses at most. */
    @Transactional(readOnly = true)
    public AdminCourseList list() {
        return new AdminCourseList(repository.findAll(Sort.by("id")).stream()
                .map(CourseService::adminView)
                .toList());
    }

    @Transactional(readOnly = true)
    public AdminCourse get(String courseId) {
        return adminView(find(courseId));
    }

    /** Replaces the whole editable document: a field left out is cleared. */
    @Transactional
    public AdminCourse update(long adminId, String courseId, CourseDocument document) {
        CourseEntity course = find(courseId);
        if (!course.getSlug().equals(document.slug()) && repository.existsBySlug(document.slug())) {
            throw new SlugTakenException();
        }
        requireExactInstallments(document);
        apply(document, course);
        log.info("Admin {} updated Course {}", adminId, course.getId());
        return adminView(course);
    }

    /** Only a Draft, which no one but Admins has ever seen. */
    @Transactional
    public void delete(long adminId, String courseId) {
        CourseEntity course = find(courseId);
        if (course.getStatus() != CourseStatus.DRAFT) {
            throw new CourseNotDraftException();
        }
        repository.delete(course);
        log.info("Admin {} deleted Course {}", adminId, course.getId());
    }

    /** Takes the id as the path carries it, so that an id of any shape answers like an unknown one. */
    private CourseEntity find(String courseId) {
        return PathIds.parse(courseId).flatMap(repository::findById).orElseThrow(CourseNotFoundException::new);
    }

    /** The installment is {@code priceCents / maxInstallments} with no remainder, once both are set. */
    private static void requireExactInstallments(CourseDocument document) {
        if (document.priceCents() != null && document.maxInstallments() != null
                && document.priceCents() % document.maxInstallments() != 0) {
            throw new PriceNotDivisibleByInstallmentsException();
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
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static AdminCourse adminView(CourseEntity course) {
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
                course.getStatus(),
                readinessOf(course),
                course.getComingSoonAt(),
                course.getOnSaleAt());
    }

    /** Only the states the Course can still move to, and none at all once it is On sale. */
    private static Readiness readinessOf(CourseEntity course) {
        return switch (course.getStatus()) {
            case DRAFT -> new Readiness(CourseRequirements.missingToGoComingSoon(course),
                    CourseRequirements.missingToGoOnSale(course));
            case COMING_SOON -> new Readiness(null, CourseRequirements.missingToGoOnSale(course));
            case ON_SALE -> null;
        };
    }

    private static FaqEntry faqEntry(CourseFaqEntry entry) {
        return new FaqEntry(entry.question(), entry.answer());
    }
}
