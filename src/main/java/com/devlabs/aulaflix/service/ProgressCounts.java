package com.devlabs.aulaflix.service;

import com.devlabs.aulaflix.domain.Standing;
import com.devlabs.aulaflix.dto.Progress;

/**
 * The Progress maths, done once here, so the web never does it. Only a published Lesson takes a mark, and none is ever
 * unpublished, so the completed Lessons are always some of the published ones.
 */
final class ProgressCounts {

    private ProgressCounts() {
    }

    /**
     * The percent is rounded down, so it reaches 100 only once every Lesson is completed, "Em breve" ones included:
     * the bar never shows more than the Student has done. A Course without Lessons has nothing to start.
     */
    static Progress of(int completed, int published, int total) {
        int percent = total == 0 ? 0 : (int) ((long) completed * 100 / total);
        return new Progress(completed, published, total, percent, standing(completed, published, total));
    }

    private static Standing standing(int completed, int published, int total) {
        if (completed == 0) {
            return Standing.NOT_STARTED;
        }
        if (completed == total) {
            return Standing.FINISHED;
        }
        return completed == published ? Standing.CAUGHT_UP : Standing.IN_PROGRESS;
    }
}
