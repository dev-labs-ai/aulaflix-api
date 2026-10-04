package com.devlabs.aulaflix.domain;

/** A Course moves forward only: from Draft to Coming soon to On sale, or from Draft straight to On sale. */
public enum CourseStatus {
    DRAFT,
    COMING_SOON,
    ON_SALE
}
