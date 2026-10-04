package com.devlabs.aulaflix.exception;

/** One invalid field of a request, with a code from the API contract: {@code required}, {@code too-long}, … */
public record FieldViolation(String field, String code) {
}
