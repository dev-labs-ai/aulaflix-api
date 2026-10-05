package com.devlabs.aulaflix.exception;

/** No Student Account has the email: it has no Account, or it is an Admin's. The person signs up first. */
public class StudentAccountRequiredException extends RuntimeException {

    public StudentAccountRequiredException() {
        super("No Student Account has this email");
    }
}
