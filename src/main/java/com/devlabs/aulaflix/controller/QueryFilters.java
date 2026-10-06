package com.devlabs.aulaflix.controller;

import java.util.List;
import java.util.Optional;

import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;
import com.devlabs.aulaflix.service.PathIds;

/**
 * The optional filters of a list's query, read as the web sends them: a parameter left out filters nothing, and a value
 * of the wrong shape is 400 {@code invalid-request}, with {@code invalid-format} on the parameter. An id is the
 * exception: one of any shape answers like an unknown one, so it matches nothing.
 */
final class QueryFilters {

    private QueryFilters() {
    }

    /** One of the enum's constants, by its exact name. */
    static <E extends Enum<E>> Optional<E> constant(String parameter, String value, Class<E> type) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value));
        } catch (IllegalArgumentException unknown) {
            throw invalidFormat(parameter);
        }
    }

    /** Only {@code true} or {@code false}. */
    static Optional<Boolean> trueOrFalse(String parameter, String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value) {
            case "true" -> Optional.of(true);
            case "false" -> Optional.of(false);
            default -> throw invalidFormat(parameter);
        };
    }

    /** The id, when it is given in a shape some row could have. */
    static Optional<Long> id(String value) {
        return Optional.ofNullable(value).flatMap(PathIds::parse);
    }

    /** Whether the id is given in a shape no row could have, so that the list matches nothing. */
    static boolean matchesNothing(String id) {
        return id != null && PathIds.parse(id).isEmpty();
    }

    static InvalidRequestException invalidFormat(String parameter) {
        return new InvalidRequestException(List.of(new FieldViolation(parameter, "invalid-format")));
    }
}
