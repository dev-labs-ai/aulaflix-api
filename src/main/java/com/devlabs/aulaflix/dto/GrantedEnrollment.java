package com.devlabs.aulaflix.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.EnrollmentEndReason;
import com.devlabs.aulaflix.domain.EnrollmentStatus;

/** The Enrollment an Order's payment granted, active or ended; {@code GET /v1/admin/enrollments/{id}} has the rest. */
public record GrantedEnrollment(
        long id,
        EnrollmentStatus status,
        Instant startedAt,

        @Schema(description = "When it ended; left out while it is active")
        Instant endedAt,

        @Schema(description = "Why it ended; left out while it is active")
        EnrollmentEndReason endReason) {
}
