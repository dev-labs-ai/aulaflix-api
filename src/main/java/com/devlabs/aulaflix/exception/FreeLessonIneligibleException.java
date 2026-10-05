package com.devlabs.aulaflix.exception;

/** Only a published Lesson of the Course can be its Free lesson: not an unpublished one, another Course's, or none. */
public class FreeLessonIneligibleException extends RuntimeException {

    public FreeLessonIneligibleException() {
        super("The Free lesson must be a published Lesson of the Course");
    }
}
