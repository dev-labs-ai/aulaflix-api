package com.devlabs.aulaflix.exception;

import java.time.Duration;

/**
 * Asaas could not take the call: it was down, too slow, or answered 5xx or 429. The reason names the call and the
 * status or the failure, never a CPF, a key or a URL.
 */
public class PaymentUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    /** What became of the Order, such as "Order K7M2Q9XA cancelled", and the failure, whose wait it keeps. */
    public PaymentUnavailableException(String outcome, AsaasUnavailableException failure) {
        super(outcome + "; " + failure.getMessage(), failure);
        this.retryAfter = failure.retryAfter();
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
