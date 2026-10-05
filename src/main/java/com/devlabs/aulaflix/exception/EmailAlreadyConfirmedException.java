package com.devlabs.aulaflix.exception;

public class EmailAlreadyConfirmedException extends RuntimeException {

    public EmailAlreadyConfirmedException() {
        super("The Account's email is already confirmed");
    }
}
