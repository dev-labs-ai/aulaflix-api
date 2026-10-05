package com.devlabs.aulaflix.exception;

/** A webhook delivery whose body is over 256 KB, far more than any event Asaas sends. */
public class WebhookBodyTooLargeException extends RuntimeException {

    public WebhookBodyTooLargeException() {
        super("The webhook's body is over 256 KB");
    }
}
