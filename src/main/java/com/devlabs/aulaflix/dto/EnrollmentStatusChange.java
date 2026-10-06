package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.EnrollmentStatus;

/** Ends a manual Enrollment, with a note; ending an ended one changes nothing. */
public record EnrollmentStatusChange(
        @NotNull(message = "required")
        @Schema(description = "Only `ENDED`; any other status is `invalid-format`.", example = "ENDED")
        EnrollmentStatus status,

        @NotBlank(message = "required")
        @Schema(description = "Why. Trimmed; at most 500 characters once trimmed.",
                maxLength = ManualEnrollmentRequest.NOTE_MAX_CHARACTERS, example = "Cortesia encerrada.")
        String note) {
}
