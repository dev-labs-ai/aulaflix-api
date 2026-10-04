package com.devlabs.aulaflix.domain;

/**
 * A Course moves forward only: from Draft to Coming soon to On sale, or from Draft straight to On sale. The states are
 * declared in that order, so a later one compares greater.
 */
public enum CourseStatus {
    DRAFT,
    COMING_SOON,
    ON_SALE
}
