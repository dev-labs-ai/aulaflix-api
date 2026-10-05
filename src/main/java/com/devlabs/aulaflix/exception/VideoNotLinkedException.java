package com.devlabs.aulaflix.exception;

/** The Lesson has no video to play. */
public class VideoNotLinkedException extends RuntimeException {

    public VideoNotLinkedException() {
        super("The Lesson has no video yet");
    }
}
