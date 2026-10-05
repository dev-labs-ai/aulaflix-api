package com.devlabs.aulaflix.exception;

import java.time.Duration;

/**
 * A request past a rate limit, refused until the window that counted it ends. Only the window's first refusal is
 * logged, since a flood repeats the others.
 */
public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;
    private final boolean firstOfItsWindow;

    /** The message names the limit and the key it counts, which never hold an email: it goes into the log. */
    public RateLimitedException(String message, Duration retryAfter, boolean firstOfItsWindow) {
        super(message);
        this.retryAfter = retryAfter;
        this.firstOfItsWindow = firstOfItsWindow;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public boolean firstOfItsWindow() {
        return firstOfItsWindow;
    }
}
