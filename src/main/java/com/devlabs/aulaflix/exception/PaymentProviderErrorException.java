package com.devlabs.aulaflix.exception;

/**
 * Asaas refused a call with a 4xx other than 429, or answered something the API cannot read: a fault that a retry will
 * not fix. The reason names the call, the status and Asaas's error codes, never a CPF, a key or a URL.
 */
public class PaymentProviderErrorException extends RuntimeException {

    /** What became of the Order, such as "Order K7M2Q9XA cancelled", and the refusal. */
    public PaymentProviderErrorException(String outcome, AsaasRefusedException refusal) {
        super(outcome + "; " + refusal.getMessage(), refusal);
    }
}
