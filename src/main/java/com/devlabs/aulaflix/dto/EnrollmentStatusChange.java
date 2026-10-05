package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.EnrollmentStatus;

/** Ends a manual Enrollment, with a note; sending the state it is already in changes nothing. */
public record EnrollmentStatusChange(
        @NotNull(message = "required")
        @Schema(example = "ENDED")
        EnrollmentStatus status,

        @NotBlank(message = "required")
        @Size(max = ManualEnrollmentRequest.NOTE_MAX_CHARACTERS, message = "too-long")
        @Schema(description = "Why. Trimmed; at most 500 characters.", example = "Cortesia encerrada.")
        String note) {
}
