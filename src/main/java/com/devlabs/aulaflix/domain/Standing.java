package com.devlabs.aulaflix.domain;

/** Where a Student stands in a Course, read from their Progress, in the order they get there. */
public enum Standing {

    /** No Lesson completed yet. */
    NOT_STARTED,

    /** Some Lessons completed, while some published ones are not. */
    IN_PROGRESS,

    /** Caught up: every published Lesson completed, while some Lessons are still "Em breve". */
    CAUGHT_UP,

    /** Finished: every Lesson of the Course completed. */
    FINISHED
}
