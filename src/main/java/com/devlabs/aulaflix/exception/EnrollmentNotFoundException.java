package com.devlabs.aulaflix.exception;

public class EnrollmentNotFoundException extends RuntimeException {

    public EnrollmentNotFoundException() {
        super("No Enrollment has this id");
    }
}
