package com.devlabs.aulaflix.exception;

public class LessonSlugTakenException extends RuntimeException {

    public LessonSlugTakenException() {
        super("Another Lesson of this Course already has this slug");
    }
}
