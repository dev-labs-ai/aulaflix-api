package com.devlabs.aulaflix.exception;

/** The Course has been announced or launched, and only a Draft may still be deleted. */
public class CourseNotDraftException extends RuntimeException {

    public CourseNotDraftException() {
        super("The Course is no longer a Draft");
    }
}
