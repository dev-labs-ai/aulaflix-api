package com.devlabs.aulaflix.exception;

/** What the Student asks for needs an active Enrollment in the Course, as any Lesson but the Free one does. */
public class EnrollmentRequiredException extends RuntimeException {

    public EnrollmentRequiredException() {
        super("This needs an active Enrollment in the Course");
    }
}
