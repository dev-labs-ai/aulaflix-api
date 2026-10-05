package com.devlabs.aulaflix.exception;

/** A webhook delivery without Asaas's token, so not from Asaas. */
public class InvalidWebhookTokenException extends RuntimeException {

    public InvalidWebhookTokenException() {
        super("The delivery lacks a valid asaas-access-token");
    }
}
