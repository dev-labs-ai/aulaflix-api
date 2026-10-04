package com.devlabs.aulaflix.exception;

import java.time.Duration;

/** Too many failed sign-ins for one email; checked before the password, so even the right one is refused. */
public class SignInBlockedException extends RuntimeException {

    private final Duration retryAfter;

    public SignInBlockedException(Duration retryAfter) {
        super("Sign-in is blocked for this email");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
