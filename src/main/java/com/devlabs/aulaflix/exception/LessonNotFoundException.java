package com.devlabs.aulaflix.exception;

/** No Lesson has the id; an id that cannot be one answers the same. */
public class LessonNotFoundException extends RuntimeException {

    public LessonNotFoundException() {
        super("No Lesson has this id");
    }
}
