package com.devlabs.aulaflix.exception;

/**
 * The uploaded MP4's index does not come before its media: its media comes first, or the movie is fragmented. A player
 * must then fetch more than the start of the file before it can play or seek.
 */
public class VideoNotFaststartException extends RuntimeException {

    public VideoNotFaststartException() {
        super("The index does not come before the media");
    }
}
