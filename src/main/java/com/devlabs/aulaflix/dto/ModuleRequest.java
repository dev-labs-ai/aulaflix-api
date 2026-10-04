package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** What an Admin sets on a Module: only its title, the same to add one as to rename it. */
public record ModuleRequest(
        @NotBlank(message = "required")
        @Size(max = TITLE_MAX_CHARACTERS, message = "too-long")
        @Schema(example = "Fundamentos")
        String title) {

    static final int TITLE_MAX_CHARACTERS = 120;
}
