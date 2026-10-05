package com.devlabs.aulaflix.exception;

/** The key was not issued for the Lesson, or nothing was uploaded under it. */
public class VideoNotFoundException extends RuntimeException {

    public VideoNotFoundException() {
        super("No video was uploaded under this key for this Lesson");
    }
}
