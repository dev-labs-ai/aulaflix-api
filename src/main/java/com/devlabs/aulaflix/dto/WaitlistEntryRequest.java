package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** A Visitor's join; only a missing field is refused here: the service normalizes the email, then checks its shape. */
public record WaitlistEntryRequest(
        @NotNull(message = "required")
        @Schema(example = "3")
        Long courseId,

        @NotNull(message = "required")
        @Schema(description = "Trimmed and lower-cased; at most 254 characters.", example = "bia@example.com")
        String email) {
}
