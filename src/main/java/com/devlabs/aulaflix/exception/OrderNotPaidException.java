package com.devlabs.aulaflix.exception;

/** Only a paid Order is refunded: this one was never paid, or a Reversal took its money back already. */
public class OrderNotPaidException extends RuntimeException {

    public OrderNotPaidException() {
        super("The Order is not paid");
    }
}
