package com.devlabs.aulaflix.exception;

/** Only a Coming soon or On sale Course takes Enrollments: no Course has the id, or it is a Draft. */
public class CourseNotEnrollableException extends RuntimeException {

    public CourseNotEnrollableException() {
        super("The Course is unknown or a Draft");
    }
}
