package com.devlabs.aulaflix.exception;

/** Only an Order awaiting payment is cancelled: this one was paid, expired, or declined. */
public class OrderNotAwaitingPaymentException extends RuntimeException {

    public OrderNotAwaitingPaymentException() {
        super("The Order is not awaiting payment");
    }
}
