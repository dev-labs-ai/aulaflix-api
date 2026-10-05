package com.devlabs.aulaflix.exception;

/** No Waitlist entry of the Course holds the Student's Account email, or no Course has the id. */
public class NotOnWaitlistException extends RuntimeException {

    public NotOnWaitlistException() {
        super("The Student is not on the Course's Waitlist");
    }
}
