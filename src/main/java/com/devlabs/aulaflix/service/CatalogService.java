package com.devlabs.aulaflix.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.dto.CourseDetail;
import com.devlabs.aulaflix.dto.CourseList;
import com.devlabs.aulaflix.dto.CourseListItem;
import com.devlabs.aulaflix.dto.CoursePricing;
import com.devlabs.aulaflix.dto.FaqEntry;
import com.devlabs.aulaflix.dto.SyllabusModule;
import com.devlabs.aulaflix.exception.CourseNotFoundException;
import com.devlabs.aulaflix.repository.CourseRepository;
import com.devlabs.aulaflix.repository.LessonRepository;
import com.devlabs.aulaflix.repository.LessonRepository.CourseLessonCount;
import com.devlabs.aulaflix.repository.ModuleRepository;

/** The public catalog: the Courses Visitors see, the same for everyone. A Draft is not part of it. */
@Service
public class CatalogService {

    private final CourseRepository courses;
    private final ModuleRepository modules;
    private final LessonRepository lessons;

    public CatalogService(CourseRepository courses, ModuleRepository modules, LessonRepository lessons) {
        this.courses = courses;
        this.modules = modules;
        this.lessons = lessons;
    }

    /**
     * Unpaginated: the catalog holds a few dozen Courses at most, and the web filters them itself. The Lessons are
     * counted after the Courses are read: a Course listed On sale stays so, and so has its count.
     */
    @Transactional(readOnly = true)
    public CourseList list() {
        List<CourseEntity> catalog = courses.findCatalog();
        Map<Long, Long> lessonCounts = lessons.countLessonsOfOnSaleCourses().stream()
                .collect(Collectors.toMap(CourseLessonCount::getCourseId, CourseLessonCount::getLessonCount));
        return new CourseList(catalog.stream()
                .map(course -> listItem(course, lessonCounts.getOrDefault(course.getId(), 0L)))
                .toList());
    }

    /** A Draft answers like a slug no Course has, so probing slugs reveals no unannounced Course. */
    @Transactional(readOnly = true)
    public CourseDetail detail(String slug) {
        CourseEntity course = courses.findBySlug(slug)
                .filter(found -> found.getStatus() != CourseStatus.DRAFT)
                .orElseThrow(CourseNotFoundException::new);
        return detail(course, course.getStatus() == CourseStatus.ON_SALE ? syllabusOf(course) : null);
    }

    private List<SyllabusModule> syllabusOf(CourseEntity course) {
        return Syllabus.of(modules.findByCourseIdOrderByPosition(course.getId()),
                lessons.findByCourseIdOrderByPosition(course.getId()));
    }

    private static CourseListItem listItem(CourseEntity course, long lessonCount) {
        boolean onSale = course.getStatus() == CourseStatus.ON_SALE;
        boolean comingSoon = course.getStatus() == CourseStatus.COMING_SOON;
        return new CourseListItem(
                course.getId(),
                course.getSlug(),
                course.getTitle(),
                course.getSummary(),
                course.getArea(),
                course.getIcon(),
                course.getTone(),
                course.getStatus(),
                onSale ? Pricing.of(course) : null,
                onSale ? Math.toIntExact(lessonCount) : null,
                comingSoon ? course.getPlannedTopics().size() : null);
    }

    /**
     * The Syllabus is null unless the Course is On sale; it holds every Lesson, so they are counted from it. Each
     * line of the answer is one part of the page: what the Course is, what it sells, what it says, and what it holds.
     */
    private static CourseDetail detail(CourseEntity course, List<SyllabusModule> syllabus) {
        boolean onSale = course.getStatus() == CourseStatus.ON_SALE;
        boolean comingSoon = course.getStatus() == CourseStatus.COMING_SOON;
        return new CourseDetail(course.getId(), course.getSlug(), course.getTitle(), course.getSummary(),
                course.getArea(), course.getIcon(), course.getTone(), course.getStatus(),
                onSale ? Pricing.of(course) : null, onSale ? lessonCountOf(syllabus) : null,
                comingSoon ? course.getPlannedTopics().size() : null,
                course.getAbout(), course.getLearn(), course.getAudience(), faqOf(course),
                onSale ? course.getFreeLessonId() : null, syllabus, comingSoon ? course.getPlannedTopics() : null);
    }

    private static int lessonCountOf(List<SyllabusModule> syllabus) {
        return syllabus.stream().mapToInt(module -> module.lessons().size()).sum();
    }

    private static List<FaqEntry> faqOf(CourseEntity course) {
        return course.getFaq().stream().map(entry -> new FaqEntry(entry.question(), entry.answer())).toList();
    }
}
