package com.devlabs.aulaflix.domain.entity;

/** The outbox's templates, each email naming the one it was rendered from. */
public enum EmailTemplate {
    /** The link that confirms a Student's email, which is also the welcome email. */
    CONFIRMATION_LINK,
    /** The Student's record that an Order was paid and its Course opened. */
    PURCHASE_CONFIRMATION
}
