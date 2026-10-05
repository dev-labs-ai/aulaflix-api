package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import io.swagger.v3.oas.annotations.media.Schema;

/** The violation messages are the API's field codes, which the 400 problem lists under {@code errors}. */
public record SignInRequest(
        @NotBlank(message = "required")
        @Schema(example = "bia@example.com")
        String email,

        @NotEmpty(message = "required")
        @Schema(description = "At most 72 bytes in UTF-8, the longest any Account can have.",
                example = "correct horse battery")
        String password) {
}
