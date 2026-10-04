package com.devlabs.aulaflix.exception;

/** No Admin Account has the email: none at all, or a Student's. Only the admin command asks. */
public class AdminNotFoundException extends RuntimeException {

    public AdminNotFoundException() {
        super("No Admin Account has this email");
    }
}
