package com.devlabs.aulaflix.exception;

/** The Lesson has no video yet, and a Lesson is published only with one. */
public class VideoRequiredException extends RuntimeException {

    public VideoRequiredException() {
        super("A Lesson is published only with a video");
    }
}
