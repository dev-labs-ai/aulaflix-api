package com.devlabs.aulaflix.exception;

/** An Enrollment granted by a paid Order ends only with its Order: by a Refund or a Reversal, never by hand. */
public class PaidEnrollmentException extends RuntimeException {

    public PaidEnrollmentException() {
        super("An Enrollment granted by an Order is not ended by hand");
    }
}
