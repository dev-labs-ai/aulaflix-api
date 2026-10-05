package com.devlabs.aulaflix.service;

import java.util.List;

import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.stereotype.Component;

import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;

/**
 * Refuses a new password HIBP has seen in a breach, as the field it was sent in. It is asked only once the rest of
 * the request is valid, so that a refused request never reaches HIBP, and before any code is tried, so that a refused
 * password never spends one of the code's tries.
 */
@Component
class BreachedPasswords {

    private final CompromisedPasswordChecker checker;

    BreachedPasswords(CompromisedPasswordChecker checker) {
        this.checker = checker;
    }

    void requireUnbreached(String field, String password) {
        if (checker.check(password).isCompromised()) {
            throw new InvalidRequestException(List.of(new FieldViolation(field, "breached")));
        }
    }
}
