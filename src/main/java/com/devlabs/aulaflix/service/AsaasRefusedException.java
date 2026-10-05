package com.devlabs.aulaflix.service;

import java.util.List;
import java.util.Locale;

/**
 * Asaas refused a call with a 4xx other than 429, or answered something the API cannot read. A retry will not change
 * it. The message names the call, the status and Asaas's error codes, which never hold the data sent.
 */
public final class AsaasRefusedException extends RuntimeException {

    private final List<String> errorCodes;

    AsaasRefusedException(String operation, int status, List<String> errorCodes) {
        super("Asaas refused %s: HTTP %d %s".formatted(operation, status, errorCodes));
        this.errorCodes = List.copyOf(errorCodes);
    }

    AsaasRefusedException(String operation, String reason) {
        super("Asaas answered %s with %s".formatted(operation, reason));
        this.errorCodes = List.of();
    }

    /** Whether Asaas refused the CPF, which it names {@code cpfCnpj}, as in {@code invalid_cpfCnpj}. */
    public boolean refusedTheCpf() {
        return errorCodes.stream().anyMatch(code -> code.toLowerCase(Locale.ROOT).contains("cpfcnpj"));
    }
}
