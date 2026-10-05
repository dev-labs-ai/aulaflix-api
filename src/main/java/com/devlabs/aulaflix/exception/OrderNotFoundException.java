package com.devlabs.aulaflix.exception;

/** No Order of the Student has the code, which may be another Student's. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException() {
        super("The Student has no Order with the code");
    }
}
