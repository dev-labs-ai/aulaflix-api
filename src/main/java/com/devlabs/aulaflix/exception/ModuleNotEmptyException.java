package com.devlabs.aulaflix.exception;

/** The Module still has Lessons, which the Admin must move or delete before the Module. */
public class ModuleNotEmptyException extends RuntimeException {

    public ModuleNotEmptyException() {
        super("The Module still has Lessons");
    }
}
