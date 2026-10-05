package com.devlabs.aulaflix.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;

/** Normalizes and checks Account input the same way the web does. */
final class AccountInputRules {

    private static final int EMAIL_MAX_CHARACTERS = 254;
    private static final int NAME_MAX_CHARACTERS = 80;
    private static final int PASSWORD_MIN_CHARACTERS = 8;
    /** bcrypt reads no further than 72 bytes, so no Account may have a longer password. */
    private static final int PASSWORD_MAX_UTF8_BYTES = 72;

    /** Unicode whitespace, as JavaScript's {@code \s} matches it in the web's own normalization. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    /** The web's shape: something@something.tld, with no whitespace. */
    private static final Pattern EMAIL_SHAPE =
            Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+", Pattern.UNICODE_CHARACTER_CLASS);

    private AccountInputRules() {
    }

    /** Refuses the request with every invalid field at once. */
    static void requireValid(List<FieldViolation> violations) {
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations);
        }
    }

    static String normalizeEmail(String email) {
        return collapseWhitespace(email).toLowerCase(Locale.ROOT);
    }

    static String normalizeName(String name) {
        return collapseWhitespace(name);
    }

    /** Checks the normalized email and name, and the password as typed. */
    static List<FieldViolation> violations(String email, String name, String password) {
        return Stream.of(emailViolation(email), nameViolation(name), newPasswordViolation(password))
                .flatMap(Optional::stream)
                .toList();
    }

    static List<FieldViolation> nameViolations(String name) {
        return nameViolation(name).stream().toList();
    }

    static List<FieldViolation> emailViolations(String email) {
        return emailViolation(email).stream().toList();
    }

    static List<FieldViolation> newPasswordViolations(String password) {
        return newPasswordViolation(password).stream().toList();
    }

    /**
     * Checks the normalized email and the password as typed. The request already requires the password, and a sign-in
     * password has no minimum: only its maximum, since no Account can have a longer one.
     */
    static List<FieldViolation> signInViolations(String email, String password) {
        return Stream.of(emailViolation(email), signInPasswordViolation(password))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<FieldViolation> emailViolation(String email) {
        if (email.isEmpty()) {
            return violation("email", "required");
        }
        if (characters(email) > EMAIL_MAX_CHARACTERS) {
            return violation("email", "too-long");
        }
        if (!EMAIL_SHAPE.matcher(email).matches()) {
            return violation("email", "invalid-email");
        }
        return Optional.empty();
    }

    private static Optional<FieldViolation> nameViolation(String name) {
        if (name.isEmpty()) {
            return violation("name", "required");
        }
        if (characters(name) > NAME_MAX_CHARACTERS) {
            return violation("name", "too-long");
        }
        return Optional.empty();
    }

    private static Optional<FieldViolation> newPasswordViolation(String password) {
        if (password.isEmpty()) {
            return violation("password", "required");
        }
        if (characters(password) < PASSWORD_MIN_CHARACTERS) {
            return violation("password", "too-short");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_UTF8_BYTES) {
            return violation("password", "too-long");
        }
        return Optional.empty();
    }

    private static Optional<FieldViolation> signInPasswordViolation(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_UTF8_BYTES) {
            return violation("password", "too-long");
        }
        return Optional.empty();
    }

    private static Optional<FieldViolation> violation(String field, String code) {
        return Optional.of(new FieldViolation(field, code));
    }

    private static int characters(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String collapseWhitespace(String value) {
        return WHITESPACE.matcher(value).replaceAll(" ").trim();
    }
}
