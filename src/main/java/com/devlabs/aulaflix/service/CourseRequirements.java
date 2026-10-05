package com.devlabs.aulaflix.service;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.entity.CourseEntity;

/**
 * What a Course needs before it may move to each state, named after the document's fields. The title is not listed:
 * no Course is ever without one.
 */
final class CourseRequirements {

    private static final List<Requirement> CONTENT = List.of(
            new Requirement("summary", course -> course.getSummary() != null && !course.getSummary().isBlank()),
            new Requirement("area", course -> course.getArea() != null),
            new Requirement("icon", course -> course.getIcon() != null),
            new Requirement("tone", course -> course.getTone() != null),
            new Requirement("about", course -> !course.getAbout().isEmpty()),
            new Requirement("learn", course -> !course.getLearn().isEmpty()),
            new Requirement("audience", course -> !course.getAudience().isEmpty()));

    private static final List<Requirement> COMING_SOON = Stream.concat(CONTENT.stream(), Stream.of(
                    new Requirement("plannedTopics", course -> !course.getPlannedTopics().isEmpty())))
            .toList();

    /**
     * A price that divides by the installments, and a published Free lesson. The service refuses any other price, and
     * any other Free lesson, whatever the state, so here it is enough that both are set.
     */
    private static final List<Requirement> ON_SALE = Stream.concat(CONTENT.stream(), Stream.of(
                    new Requirement("priceCents", course -> course.getPriceCents() != null),
                    new Requirement("maxInstallments", course -> course.getMaxInstallments() != null),
                    new Requirement("freeLessonId", course -> course.getFreeLessonId() != null)))
            .toList();

    private CourseRequirements() {
    }

    /** The fields the Course lacks to be in the state: to move there, or to stay there. A Draft needs nothing. */
    static List<String> missingFor(CourseStatus state, CourseEntity course) {
        return switch (state) {
            case DRAFT -> List.of();
            case COMING_SOON -> missing(COMING_SOON, course);
            case ON_SALE -> missing(ON_SALE, course);
        };
    }

    private static List<String> missing(List<Requirement> requirements, CourseEntity course) {
        return requirements.stream()
                .filter(requirement -> !requirement.isMetBy().test(course))
                .map(Requirement::field)
                .toList();
    }

    private record Requirement(String field, Predicate<CourseEntity> isMetBy) {
    }
}
