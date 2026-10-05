package com.devlabs.aulaflix.domain;

/**
 * Why an Enrollment ended: its Order was refunded, charged back, or reversed by an upheld Pix cautionary block; or an
 * Admin ended a manual one by hand, with a note.
 */
public enum EnrollmentEndReason {
    REFUND,
    CHARGEBACK,
    PIX_BLOCK_UPHELD,
    MANUAL
}
