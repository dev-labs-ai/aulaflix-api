package com.devlabs.aulaflix.exception;

/** The uploaded MP4's duration, rounded to the nearest second, is 0. */
public class VideoTooShortException extends RuntimeException {

    public VideoTooShortException() {
        super("The video lasts under a second");
    }
}
