package com.devlabs.aulaflix.exception;

/** The Course has left Draft, so its slug, the web's address for it, can no longer change. */
public class SlugFrozenException extends RuntimeException {

    public SlugFrozenException() {
        super("The slug of a Course that is no longer a Draft cannot change");
    }
}
