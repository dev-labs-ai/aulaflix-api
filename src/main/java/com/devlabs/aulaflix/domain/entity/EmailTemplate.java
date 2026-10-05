package com.devlabs.aulaflix.domain.entity;

/** The outbox's templates, each email naming the one it was rendered from. */
public enum EmailTemplate {
    /** The link that confirms a Student's email, which is also the welcome email. */
    CONFIRMATION_LINK,
    /** A 6-digit code that resets or changes a Student's password. */
    VERIFICATION_CODE,
    /** The notice that a Student's password was reset or changed, so that they notice if it wasn't them. */
    PASSWORD_CHANGED,
    /** The Student's record that an Order was paid and its Course opened. */
    PURCHASE_CONFIRMATION,
    /** The Student's notice that an Order was refunded, and whether its Course closed with it. */
    REFUND_NOTICE,
    /** Every Admin's alert that an Order was a Duplicate payment, to refund by hand. */
    DUPLICATE_PAYMENT_ALERT
}
