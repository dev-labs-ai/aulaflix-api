package com.devlabs.aulaflix.exception;

import java.time.Duration;

/**
 * Turnstile could not say whether a CAPTCHA token is good: {@code siteverify} was down, too slow, or answered an error.
 * The request, past a soft limit, is refused rather than let through. The reason names the status or the failure,
 * never the token nor the secret.
 */
public class CaptchaUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public CaptchaUnavailableException(String reason, Duration retryAfter) {
        super(reason);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
