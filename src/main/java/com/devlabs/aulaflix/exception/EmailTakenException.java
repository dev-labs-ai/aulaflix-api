package com.devlabs.aulaflix.exception;

public class EmailTakenException extends RuntimeException {

    public EmailTakenException() {
        super("An Account with this email already exists");
    }
}
