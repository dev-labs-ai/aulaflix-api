package com.devlabs.aulaflix.exception;

/** The Lesson is published, so its slug, the web's address for it, can no longer change. */
public class LessonSlugFrozenException extends RuntimeException {

    public LessonSlugFrozenException() {
        super("The slug of a published Lesson cannot change");
    }
}
