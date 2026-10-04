package com.devlabs.aulaflix.exception;

/** The price leaves a remainder at the maximum installments, so the advertised installment would not be exact. */
public class PriceNotDivisibleByInstallmentsException extends RuntimeException {

    public PriceNotDivisibleByInstallmentsException() {
        super("priceCents is not divisible by maxInstallments");
    }
}
