package com.devlabs.aulaflix.exception;

/** The endpoint takes no query parameters, so a filter the caller believes it applied is refused, never ignored. */
public class QueryParametersNotAllowedException extends RuntimeException {

    public QueryParametersNotAllowedException() {
        super("This endpoint takes no query parameters");
    }
}
