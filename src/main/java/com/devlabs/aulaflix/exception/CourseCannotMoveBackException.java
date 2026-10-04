package com.devlabs.aulaflix.exception;

/** A Course only ever moves forward, so it never returns to a state it has left. */
public class CourseCannotMoveBackException extends RuntimeException {

    public CourseCannotMoveBackException() {
        super("A Course never moves back to an earlier state");
    }
}
