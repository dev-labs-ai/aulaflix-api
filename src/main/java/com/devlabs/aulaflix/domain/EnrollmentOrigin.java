package com.devlabs.aulaflix.domain;

/** How an Enrollment was granted: by an Order once paid, or by hand by an Admin, with a note saying why. */
public enum EnrollmentOrigin {
    ORDER,
    MANUAL
}
