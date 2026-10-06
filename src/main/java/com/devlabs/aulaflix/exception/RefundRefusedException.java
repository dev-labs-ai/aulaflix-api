package com.devlabs.aulaflix.exception;

import java.util.List;

/** Asaas refused the refund, for the reasons it gave; nothing changed. The message holds only Asaas's codes. */
public class RefundRefusedException extends RuntimeException {

    private final List<ProviderError> reasons;

    /** The Order's refund, and Asaas's refusal, whose errors are the reasons. */
    public RefundRefusedException(String orderCode, AsaasRefusedException refusal) {
        this(orderCode, refusal.errors().stream()
                .map(error -> new ProviderError(error.code(), error.description()))
                .toList(), refusal);
    }

    private RefundRefusedException(String orderCode, List<ProviderError> reasons, AsaasRefusedException refusal) {
        super("Asaas refused the refund of Order %s: %s".formatted(orderCode,
                reasons.stream().map(ProviderError::code).toList()), refusal);
        this.reasons = List.copyOf(reasons);
    }

    public List<ProviderError> reasons() {
        return reasons;
    }
}
