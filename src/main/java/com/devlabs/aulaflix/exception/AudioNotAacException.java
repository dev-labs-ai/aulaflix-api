package com.devlabs.aulaflix.exception;

/** The uploaded MP4 has an audio track whose samples are not all AAC; a video without audio is fine. */
public class AudioNotAacException extends RuntimeException {

    public AudioNotAacException() {
        super("The audio is not AAC");
    }
}
