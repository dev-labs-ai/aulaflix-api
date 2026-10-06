package com.devlabs.aulaflix.exception;

import java.time.Duration;

/** Asaas could not take a call: it was down, too slow, or answered 5xx or 429. A later retry may get through. */
public final class AsaasUnavailableException extends RuntimeException {

    private final Duration retryAfter;

    public AsaasUnavailableException(String operation, String reason, Duration retryAfter) {
        super("Asaas failed %s: %s".formatted(operation, reason));
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
