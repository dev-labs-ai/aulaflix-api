package com.devlabs.aulaflix.exception;

/** The Enrollment has ended, and an ending is final: access comes back only through a new Enrollment. */
public class EnrollmentEndedException extends RuntimeException {

    public EnrollmentEndedException() {
        super("The Enrollment has ended");
    }
}
