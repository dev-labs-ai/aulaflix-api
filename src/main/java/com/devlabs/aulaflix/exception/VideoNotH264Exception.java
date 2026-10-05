package com.devlabs.aulaflix.exception;

/** The uploaded MP4 has no video track, or one whose samples are not all H.264. */
public class VideoNotH264Exception extends RuntimeException {

    public VideoNotH264Exception() {
        super("The video is not H.264");
    }
}
