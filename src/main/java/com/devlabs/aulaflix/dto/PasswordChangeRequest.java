package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Only a missing field is refused here: the service reports every invalid field at once. The violation messages are
 * the API's field codes.
 */
public record PasswordChangeRequest(
        @NotNull(message = "required")
        @Schema(description = "The 6 digits from the email.", example = "042817")
        String code,

        @NotNull(message = "required")
        @Schema(description = "8 characters to 72 bytes in UTF-8, and not found in a known data breach.",
                example = "a brand new passphrase")
        String newPassword) {
}
