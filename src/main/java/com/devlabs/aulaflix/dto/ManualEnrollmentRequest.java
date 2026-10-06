package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The violation messages are the API's field codes. The Course's id is read as text, so that an id of any shape answers
 * like an unknown Course.
 */
public record ManualEnrollmentRequest(
        @NotBlank(message = "required")
        @Schema(description = "A Student Account's email, matched trimmed and lower-cased.",
                example = "bia@example.com")
        String email,

        @NotNull(message = "required")
        @Schema(types = "integer", format = "int64", example = "3")
        String courseId,

        @NotBlank(message = "required")
        @Size(max = NOTE_MAX_CHARACTERS, message = "too-long")
        @Schema(description = "Why: the only record of it. Trimmed; at most 500 characters.",
                example = "Chargeback ganho no pedido K7M2Q9XA.")
        String note) {

    public static final int NOTE_MAX_CHARACTERS = 500;
}
