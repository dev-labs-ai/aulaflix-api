package com.devlabs.aulaflix.exception;

/** The request lacks the BFF's key, so it did not come from the BFF, the API's only client. */
public class InvalidBffKeyException extends RuntimeException {

    public InvalidBffKeyException() {
        super("The request lacks a valid AulaFlix-BFF-Key");
    }
}
