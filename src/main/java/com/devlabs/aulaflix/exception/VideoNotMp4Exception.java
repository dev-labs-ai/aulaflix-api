package com.devlabs.aulaflix.exception;

/** The uploaded file is not an MP4 that linking can read: the reason names what its header lacks. */
public class VideoNotMp4Exception extends RuntimeException {

    private final String reason;

    /** The reason goes into the log; it names boxes and fields of the file, never the Admin. */
    public VideoNotMp4Exception(String reason) {
        super("Not an MP4: " + reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
