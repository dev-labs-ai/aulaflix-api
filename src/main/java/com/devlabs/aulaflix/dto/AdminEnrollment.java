package com.devlabs.aulaflix.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.EnrollmentEndReason;
import com.devlabs.aulaflix.domain.EnrollmentOrigin;
import com.devlabs.aulaflix.domain.EnrollmentStatus;

/** An Enrollment as Admins see it, active or ended. A field that does not fit its origin or state is left out. */
public record AdminEnrollment(
        long id,
        EnrollmentStatus status,
        AccountSummary student,
        CourseSummary course,
        Instant startedAt,
        EnrollmentOrigin origin,

        @Schema(description = "The code of the Order whose payment granted it", example = "K7M2Q9XA")
        String orderCode,

        @Schema(description = "The Admin who granted a manual Enrollment")
        AccountSummary grantedBy,

        @Schema(description = "Why a manual Enrollment was granted")
        String grantNote,

        @Schema(description = "When it ended; left out while it is active")
        Instant endedAt,

        @Schema(description = "Why it ended; left out while it is active")
        EnrollmentEndReason endReason,

        @Schema(description = "The Admin who ended it by hand")
        AccountSummary endedBy,

        @Schema(description = "Why the Admin ended it by hand")
        String endNote) {
}
