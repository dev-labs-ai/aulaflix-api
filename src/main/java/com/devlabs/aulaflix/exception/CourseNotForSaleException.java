package com.devlabs.aulaflix.exception;

/** An Order needs an On sale Course: no Course has the id, or it is a Draft or Coming soon. */
public class CourseNotForSaleException extends RuntimeException {

    public CourseNotForSaleException() {
        super("The Course is not On sale");
    }
}
