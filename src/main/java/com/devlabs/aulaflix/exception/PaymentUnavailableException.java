package com.devlabs.aulaflix.exception;

import java.time.Duration;

/**
 * Asaas could not take the call: it was down, too slow, or answered 5xx or 429. The reason names the call and the
 * status or the failure, never a CPF, a key or a URL.
 */
public class PaymentUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public PaymentUnavailableException(String reason, Duration retryAfter) {
        super(reason);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
