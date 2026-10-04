package com.devlabs.aulaflix.exception;

/** No Course has the id or the slug; one that cannot be either, and a Draft to the public, answer the same. */
public class CourseNotFoundException extends RuntimeException {

    public CourseNotFoundException() {
        super("No Course has this id or slug");
    }
}
