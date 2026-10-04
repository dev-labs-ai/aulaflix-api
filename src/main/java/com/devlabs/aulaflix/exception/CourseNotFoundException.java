package com.devlabs.aulaflix.exception;

/** No Course has the id; an id that cannot be one answers the same. */
public class CourseNotFoundException extends RuntimeException {

    public CourseNotFoundException() {
        super("No Course has this id");
    }
}
