package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Only a missing field is refused here: the service normalizes the email and the name, then reports every invalid
 * field at once. The violation messages are the API's field codes.
 */
public record SignUpRequest(
        @NotNull(message = "required")
        @Schema(description = "Trimmed, with inner whitespace collapsed; 1 to 80 characters.", example = "Bia Souza")
        String name,

        @NotNull(message = "required")
        @Schema(description = "Trimmed and lower-cased; at most 254 characters.", example = "bia@example.com")
        String email,

        @NotNull(message = "required")
        @Schema(description = "8 characters to 72 bytes in UTF-8, and not found in a known data breach.",
                example = "correct horse battery")
        String password) {
}
