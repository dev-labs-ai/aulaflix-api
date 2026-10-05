package com.devlabs.aulaflix.exception;

/** Only a Coming soon Course takes Waitlist entries: no Course has the id, or it is a Draft, or it is On sale. */
public class WaitlistClosedException extends RuntimeException {

    public WaitlistClosedException() {
        super("The Course is unknown, a Draft or On sale");
    }
}
