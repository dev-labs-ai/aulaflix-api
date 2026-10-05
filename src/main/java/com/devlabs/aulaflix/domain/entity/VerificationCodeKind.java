package com.devlabs.aulaflix.domain.entity;

/** What a 6-digit code is for: a code of one kind never works for the other. */
public enum VerificationCodeKind {
    /** Sets a new password for a Student who forgot theirs, and signs them in. */
    RESET,
    /** Sets a new password for a signed-in Student, keeping that session. */
    CHANGE
}
