package com.devlabs.aulaflix.exception;

import java.util.List;

/**
 * The request carries a query parameter the endpoint does not take, so a filter the caller believes it applied is
 * refused, never ignored.
 */
public class QueryParametersNotAllowedException extends RuntimeException {

    private final List<String> allowed;

    /** For an endpoint that takes no query parameters. */
    public QueryParametersNotAllowedException() {
        this(List.of());
    }

    /** For an endpoint that takes these, and no other. */
    public QueryParametersNotAllowedException(List<String> allowed) {
        super("A query parameter the endpoint does not take");
        this.allowed = List.copyOf(allowed);
    }

    public List<String> allowed() {
        return allowed;
    }
}
