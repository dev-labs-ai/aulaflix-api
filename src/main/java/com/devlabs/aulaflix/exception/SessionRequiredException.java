package com.devlabs.aulaflix.exception;

/** The request came without a session, and what it asks for needs one, as any Lesson but the Free one does. */
public class SessionRequiredException extends RuntimeException {

    public SessionRequiredException() {
        super("This needs a session");
    }
}
