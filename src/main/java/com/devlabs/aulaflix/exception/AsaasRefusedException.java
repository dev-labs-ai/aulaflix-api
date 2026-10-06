package com.devlabs.aulaflix.exception;

import java.util.List;
import java.util.Locale;

/**
 * Asaas refused a call with a 4xx other than 429, or answered something the API cannot read. A retry will not change
 * it. The message names the call, the status and Asaas's error codes, which never hold the data sent; the errors'
 * descriptions are kept apart, for a caller that may show them.
 */
public final class AsaasRefusedException extends RuntimeException {

    private final List<AsaasError> errors;

    public AsaasRefusedException(String operation, int status, List<AsaasError> errors) {
        super("Asaas refused %s: HTTP %d %s".formatted(operation, status,
                errors.stream().map(AsaasError::code).toList()));
        this.errors = List.copyOf(errors);
    }

    public AsaasRefusedException(String operation, String reason) {
        super("Asaas answered %s with %s".formatted(operation, reason));
        this.errors = List.of();
    }

    /** Whether Asaas refused the CPF, which it names {@code cpfCnpj}, as in {@code invalid_cpfCnpj}. */
    public boolean refusedTheCpf() {
        return errors.stream().anyMatch(error -> error.code().toLowerCase(Locale.ROOT).contains("cpfcnpj"));
    }

    /** The errors Asaas gave, each with its code and description; none when its answer could not be read. */
    public List<AsaasError> errors() {
        return errors;
    }
}
