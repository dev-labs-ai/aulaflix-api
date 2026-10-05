package com.devlabs.aulaflix.exception;

/** The Lesson is published, so it stays: Students' Progress may count it. */
public class LessonPublishedException extends RuntimeException {

    public LessonPublishedException() {
        super("A published Lesson is never deleted");
    }
}
