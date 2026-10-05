package com.devlabs.aulaflix.exception;

/**
 * A code that is wrong, expired, voided or superseded, or none asked for, and an email without a Student Account: the
 * same refusal for each, so that it tells nobody who has an Account.
 */
public class InvalidCodeException extends RuntimeException {

    public InvalidCodeException() {
        super("The code is wrong, expired, voided or superseded, or the email has no Student Account");
    }
}
