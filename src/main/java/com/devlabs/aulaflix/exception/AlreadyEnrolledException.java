package com.devlabs.aulaflix.exception;

/** The Student already has an active Enrollment in the Course, and may have only one. */
public class AlreadyEnrolledException extends RuntimeException {

    public AlreadyEnrolledException() {
        super("The Student already has an active Enrollment in the Course");
    }
}
