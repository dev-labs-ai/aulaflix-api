package com.devlabs.aulaflix.exception;

/** The outline leaves out, adds or repeats a Module or a Lesson, as against the Course's current ones. */
public class OutlineMismatchException extends RuntimeException {

    public OutlineMismatchException() {
        super("The outline does not name exactly the Course's current Modules and Lessons");
    }
}
