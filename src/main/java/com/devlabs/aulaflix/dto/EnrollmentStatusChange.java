package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.EnrollmentStatus;

/** Ends a manual Enrollment, with a note; ending an ended one changes nothing. */
public record EnrollmentStatusChange(
        @NotNull(message = "required")
        @Schema(description = "Only `ENDED`; any other status is `invalid-format`.", example = "ENDED")
        EnrollmentStatus status,

        @NotBlank(message = "required")
        @Size(max = ManualEnrollmentRequest.NOTE_MAX_CHARACTERS, message = "too-long")
        @Schema(description = "Why. Trimmed; at most 500 characters.", example = "Cortesia encerrada.")
        String note) {
}
