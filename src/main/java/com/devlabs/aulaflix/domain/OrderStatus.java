package com.devlabs.aulaflix.domain;

/**
 * Where an Order stands. It awaits payment from the moment it is placed; a payment makes it {@code PAID}, which a
 * Refund or a Reversal may later undo. {@code EXPIRED}, {@code CANCELLED} and {@code DECLINED} Orders were never paid;
 * an expired one still becomes {@code PAID} when a payment for it is confirmed afterwards, since a payment wins.
 */
public enum OrderStatus {
    AWAITING_PAYMENT,
    PAID,
    EXPIRED,
    CANCELLED,
    DECLINED,
    REFUNDING,
    REFUNDED,
    REVERSED
}
