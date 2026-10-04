package com.devlabs.aulaflix.exception;

/** No Module has the id; an id that cannot be one answers the same. */
public class ModuleNotFoundException extends RuntimeException {

    public ModuleNotFoundException() {
        super("No Module has this id");
    }
}
