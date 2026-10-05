package com.devlabs.aulaflix.domain.entity;

/** The outbox's templates, each email naming the one it was rendered from. */
public enum EmailTemplate {
    /** The link that confirms a Student's email, which is also the welcome email. */
    CONFIRMATION_LINK,
    /** A 6-digit code that resets or changes a Student's password. */
    VERIFICATION_CODE,
    /** The notice that a Student's password was reset or changed, so that they notice if it wasn't them. */
    PASSWORD_CHANGED
}
