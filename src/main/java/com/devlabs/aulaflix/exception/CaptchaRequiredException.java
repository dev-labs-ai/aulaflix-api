package com.devlabs.aulaflix.exception;

/**
 * A request past a soft limit without a CAPTCHA token that Turnstile accepts: none, or one that is invalid, expired or
 * already spent. It is answered without {@code Retry-After}, since a token lets the request through at once.
 */
public class CaptchaRequiredException extends RuntimeException {

    public CaptchaRequiredException() {
        super("A soft limit is crossed, and the request carries no CAPTCHA token that Turnstile accepts");
    }
}
