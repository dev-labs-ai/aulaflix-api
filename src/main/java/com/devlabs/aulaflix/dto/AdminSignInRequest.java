package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import io.swagger.v3.oas.annotations.media.Schema;

/** The violation messages are the API's field codes, which the 400 problem lists under {@code errors}. */
public record AdminSignInRequest(
        @NotBlank(message = "required")
        @Schema(example = "admin@aulaflix.com.br")
        String email,

        @NotEmpty(message = "required")
        @Schema(example = "correct horse battery")
        String password) {
}
