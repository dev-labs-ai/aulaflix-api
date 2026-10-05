package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** Only a missing email is refused here: the service normalizes it, then checks its shape. */
public record PasswordResetCodeRequest(
        @NotNull(message = "required")
        @Schema(description = "Trimmed and lower-cased; at most 254 characters.", example = "bia@example.com")
        String email) {
}
