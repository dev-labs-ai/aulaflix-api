package com.devlabs.aulaflix.domain;

/**
 * An Enrollment is active until it ends, and the ending is final. The states are declared in that order, so a later
 * one compares greater.
 */
public enum EnrollmentStatus {
    ACTIVE,
    ENDED
}
