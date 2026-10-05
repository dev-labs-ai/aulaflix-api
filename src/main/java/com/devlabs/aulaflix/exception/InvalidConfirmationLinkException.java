package com.devlabs.aulaflix.exception;

/** A confirmation link that is unknown, expired, or voided by a newer one: the same refusal for each. */
public class InvalidConfirmationLinkException extends RuntimeException {

    public InvalidConfirmationLinkException() {
        super("The confirmation link is unknown, expired or voided");
    }
}
