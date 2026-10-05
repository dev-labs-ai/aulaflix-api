package com.devlabs.aulaflix.exception;

/** An unsubscribe token the API did not make, or one changed in any way: the same refusal for each. */
public class InvalidUnsubscribeLinkException extends RuntimeException {

    public InvalidUnsubscribeLinkException() {
        super("The unsubscribe token was not made by the API, or was changed");
    }
}
