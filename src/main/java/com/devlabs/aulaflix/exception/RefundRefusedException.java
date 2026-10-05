package com.devlabs.aulaflix.exception;

import java.util.List;

/** Asaas refused the refund, for the reasons it gave; nothing changed. The message holds only Asaas's codes. */
public class RefundRefusedException extends RuntimeException {

    private final List<ProviderError> reasons;

    public RefundRefusedException(String orderCode, List<ProviderError> reasons) {
        super("Asaas refused the refund of Order %s: %s".formatted(orderCode,
                reasons.stream().map(ProviderError::code).toList()));
        this.reasons = List.copyOf(reasons);
    }

    public List<ProviderError> reasons() {
        return reasons;
    }
}
