package com.devlabs.aulaflix.exception;

/**
 * Asaas refused a call with a 4xx other than 429, or answered something the API cannot read: a fault that a retry will
 * not fix. The reason names the call, the status and Asaas's error codes, never a CPF, a key or a URL.
 */
public class PaymentProviderErrorException extends RuntimeException {

    public PaymentProviderErrorException(String reason) {
        super(reason);
    }
}
