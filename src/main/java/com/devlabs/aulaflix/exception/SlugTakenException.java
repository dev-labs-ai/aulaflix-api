package com.devlabs.aulaflix.exception;

public class SlugTakenException extends RuntimeException {

    public SlugTakenException() {
        super("Another Course already has this slug");
    }
}
