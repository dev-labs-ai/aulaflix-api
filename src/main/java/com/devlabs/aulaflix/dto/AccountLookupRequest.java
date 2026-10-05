package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** In a body rather than the URL, so the email stays out of every access log. */
public record AccountLookupRequest(
        @NotNull(message = "required")
        @Schema(description = "Trimmed and lower-cased before the look-up.", example = "bia@example.com")
        String email) {
}
