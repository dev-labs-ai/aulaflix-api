package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A Visitor's join; only a missing field is refused here: the service normalizes the email, then checks its shape. The
 * Course's id is read as text, so that an id of any shape answers like an unknown Course.
 */
public record WaitlistEntryRequest(
        @NotNull(message = "required")
        @Schema(types = "integer", format = "int64", example = "3")
        String courseId,

        @NotNull(message = "required")
        @Schema(description = "Trimmed and lower-cased; at most 254 characters.", example = "bia@example.com")
        String email) {
}
