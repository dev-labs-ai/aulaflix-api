package com.devlabs.aulaflix.exception;

import java.util.List;

public class InvalidRequestException extends RuntimeException {

    private final List<FieldViolation> violations;

    public InvalidRequestException(List<FieldViolation> violations) {
        super("Invalid fields: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
