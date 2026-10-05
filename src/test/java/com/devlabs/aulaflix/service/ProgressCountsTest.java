package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import com.devlabs.aulaflix.domain.Standing;
import com.devlabs.aulaflix.dto.Progress;

/**
 * The progress bar never overstates: the percent is rounded down, and reaches 100 only once the Course is Finished.
 */
class ProgressCountsTest {

    /**
     * Every count an On sale Course can show: a Lesson at least, its Free lesson, published; any number "Em breve"; and
     * any number of the published ones completed.
     */
    private static final Generator<Counts> COUNTS = Generator.from(data -> {
        int published = data.generate(Generator.integers(1, 5_000));
        int emBreve = data.generate(Generator.integers(0, 5_000));
        int completed = data.generate(Generator.integers(0, published));
        return new Counts(completed, published, published + emBreve);
    });

    @Test
    void roundsThePercentDown() {
        PropertyChecker.forAll(COUNTS, counts -> {
            long percent = ProgressCounts.of(counts.completed(), counts.published(), counts.total()).percent();
            long done = counts.completed() * 100L;
            return percent * counts.total() <= done && done < (percent + 1) * counts.total();
        });
    }

    @Test
    void reaches100OnlyWhenFinished() {
        PropertyChecker.forAll(COUNTS, counts -> {
            Progress progress = ProgressCounts.of(counts.completed(), counts.published(), counts.total());
            return (progress.percent() == 100) == (progress.standing() == Standing.FINISHED);
        });
    }

    @Test
    void counts4Of12As33PercentCaughtUpWhenTheRestIsEmBreve() {
        assertThat(ProgressCounts.of(4, 4, 12)).isEqualTo(new Progress(4, 4, 12, 33, Standing.CAUGHT_UP));
    }

    @Test
    void hasNotStartedBeforeAnyLessonIsCompleted() {
        assertThat(ProgressCounts.of(0, 10, 12)).isEqualTo(new Progress(0, 10, 12, 0, Standing.NOT_STARTED));
    }

    @Test
    void isInProgressWhileAPublishedLessonIsLeft() {
        assertThat(ProgressCounts.of(9, 10, 12)).isEqualTo(new Progress(9, 10, 12, 75, Standing.IN_PROGRESS));
    }

    @Test
    void finishesOnlyWithEveryLessonCompleted() {
        assertThat(ProgressCounts.of(12, 12, 12)).isEqualTo(new Progress(12, 12, 12, 100, Standing.FINISHED));
    }

    @Test
    void stopsShort1PercentBelow100WithOneLessonOfManyLeft() {
        assertThat(ProgressCounts.of(199, 200, 200)).isEqualTo(new Progress(199, 200, 200, 99, Standing.IN_PROGRESS));
    }

    @Test
    void hasNothingToStartWithoutLessons() {
        assertThat(ProgressCounts.of(0, 0, 0)).isEqualTo(new Progress(0, 0, 0, 0, Standing.NOT_STARTED));
    }

    private record Counts(int completed, int published, int total) {
    }
}
