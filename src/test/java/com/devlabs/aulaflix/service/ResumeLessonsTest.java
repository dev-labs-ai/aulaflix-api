package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import com.devlabs.aulaflix.service.ResumeLessons.OutlineLesson;

/**
 * The Resume lesson, in the outline's order: the last Lesson opened while unfinished, else the next unfinished one
 * after it, else the first unfinished one, else, with every Lesson done, the first published one.
 */
class ResumeLessonsTest {

    /** Lessons 1 to 6: 1 to 4 published, 5 "Em breve", 6 published. */
    private static final List<OutlineLesson> OUTLINE = List.of(published(1), published(2), published(3), published(4),
            emBreve(5), published(6));

    /**
     * Any outline, with or without published Lessons; any of its published Lessons completed; and a last visit to none,
     * to any of its Lessons, or to a Lesson no longer in it.
     */
    private static final Generator<Place> PLACES = Generator.from(data -> {
        int size = data.generate(Generator.integers(0, 30));
        List<OutlineLesson> outline = new ArrayList<>();
        Set<Long> completed = new HashSet<>();
        for (long id = 1; id <= size; id++) {
            boolean published = data.generate(Generator.booleans());
            outline.add(new OutlineLesson(id, published));
            if (published && data.generate(Generator.booleans())) {
                completed.add(id);
            }
        }
        Long visited = data.generate(Generator.<Long>frequency(
                1, Generator.constant(null),
                4, Generator.integers(1, size + 1).map(Integer::longValue)));
        return new Place(outline, completed, visited);
    });

    @Test
    void isAlwaysAPublishedLessonWhenOneExists() {
        PropertyChecker.forAll(PLACES, place -> {
            OptionalInt resume = ResumeLessons.indexIn(place.outline(), place.completed(), place.visited());
            boolean anyPublished = place.outline().stream().anyMatch(OutlineLesson::published);
            return resume.isPresent() == anyPublished
                    && (resume.isEmpty() || place.outline().get(resume.getAsInt()).published());
        });
    }

    @Test
    void isUnfinishedWhileAnyPublishedLessonIsLeft() {
        PropertyChecker.forAll(PLACES, place -> {
            boolean anyLeft = place.outline().stream()
                    .anyMatch(lesson -> lesson.published() && !place.completed().contains(lesson.id()));
            OptionalInt resume = ResumeLessons.indexIn(place.outline(), place.completed(), place.visited());
            return !anyLeft || !place.completed().contains(place.outline().get(resume.getAsInt()).id());
        });
    }

    @Test
    void isTheFirstUnfinishedPublishedLessonBeforeAnyVisit() {
        assertThat(ResumeLessons.indexIn(OUTLINE, Set.of(1L, 2L), null)).hasValue(2);
    }

    @Test
    void isTheFirstPublishedLessonBeforeAnyVisitOrMark() {
        assertThat(ResumeLessons.indexIn(List.of(emBreve(1), published(2), published(3)), Set.of(), null))
                .hasValue(1);
    }

    @Test
    void isTheLastLessonOpenedWhileItIsNotCompleted() {
        assertThat(ResumeLessons.indexIn(OUTLINE, Set.of(1L, 2L), 4L)).hasValue(3);
    }

    @Test
    void isTheNextUnfinishedPublishedLessonAfterTheLastOneOpenedOnceThatIsCompleted() {
        assertThat(ResumeLessons.indexIn(OUTLINE, Set.of(1L, 3L, 4L), 3L)).hasValue(5);
    }

    @Test
    void skipsEmBreveLessonsAfterTheLastOneOpened() {
        assertThat(ResumeLessons.indexIn(OUTLINE, Set.of(4L), 4L)).hasValue(5);
    }

    @Test
    void goesBackToTheFirstUnfinishedPublishedLessonWhenNoneFollowsTheLastOneOpened() {
        assertThat(ResumeLessons.indexIn(OUTLINE, Set.of(1L, 3L, 4L, 6L), 4L)).hasValue(1);
    }

    @Test
    void isTheFirstPublishedLessonWithEveryLessonDone() {
        assertThat(ResumeLessons.indexIn(List.of(emBreve(1), published(2), published(3)), Set.of(2L, 3L), 3L))
                .hasValue(1);
    }

    @Test
    void ignoresALastVisitToALessonNoLongerInTheOutline() {
        assertThat(ResumeLessons.indexIn(OUTLINE, Set.of(1L), 99L)).hasValue(1);
    }

    @Test
    void hasNoneWithoutAPublishedLesson() {
        assertThat(ResumeLessons.indexIn(List.of(emBreve(1)), Set.of(), null)).isEmpty();
        assertThat(ResumeLessons.indexIn(List.of(), Set.of(), null)).isEmpty();
    }

    private static OutlineLesson published(long id) {
        return new OutlineLesson(id, true);
    }

    private static OutlineLesson emBreve(long id) {
        return new OutlineLesson(id, false);
    }

    private record Place(List<OutlineLesson> outline, Set<Long> completed, Long visited) {
    }
}
