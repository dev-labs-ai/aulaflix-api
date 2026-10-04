package com.devlabs.aulaflix.exception;

/** The BFF's request lacks the browser's IP address, or carries something else in its place. */
public class InvalidClientIpException extends RuntimeException {

    public InvalidClientIpException() {
        super("AulaFlix-Client-IP is missing or not an IP address");
    }
}
