package com.devlabs.aulaflix.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.dto.CourseDetail;
import com.devlabs.aulaflix.dto.CourseList;
import com.devlabs.aulaflix.dto.CourseListItem;
import com.devlabs.aulaflix.dto.FaqEntry;
import com.devlabs.aulaflix.exception.CourseNotFoundException;
import com.devlabs.aulaflix.repository.CourseRepository;

/** The public catalog: the Courses Visitors see, the same for everyone. A Draft is not part of it. */
@Service
public class CatalogService {

    private final CourseRepository courses;

    public CatalogService(CourseRepository courses) {
        this.courses = courses;
    }

    /** Unpaginated: the catalog holds a few dozen Courses at most, and the web filters them itself. */
    @Transactional(readOnly = true)
    public CourseList list() {
        return new CourseList(courses.findCatalog().stream().map(CatalogService::listItem).toList());
    }

    /** A Draft answers like a slug no Course has, so probing slugs reveals no unannounced Course. */
    @Transactional(readOnly = true)
    public CourseDetail detail(String slug) {
        return courses.findBySlug(slug)
                .filter(course -> course.getStatus() != CourseStatus.DRAFT)
                .map(CatalogService::detail)
                .orElseThrow(CourseNotFoundException::new);
    }

    private static CourseListItem listItem(CourseEntity course) {
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
                comingSoon ? course.getPlannedTopics().size() : null);
    }

    private static CourseDetail detail(CourseEntity course) {
        boolean comingSoon = course.getStatus() == CourseStatus.COMING_SOON;
        return new CourseDetail(
                course.getId(),
                course.getSlug(),
                course.getTitle(),
                course.getSummary(),
                course.getArea(),
                course.getIcon(),
                course.getTone(),
                course.getStatus(),
                comingSoon ? course.getPlannedTopics().size() : null,
                course.getAbout(),
                course.getLearn(),
                course.getAudience(),
                course.getFaq().stream().map(entry -> new FaqEntry(entry.question(), entry.answer())).toList(),
                comingSoon ? course.getPlannedTopics() : null);
    }
}
