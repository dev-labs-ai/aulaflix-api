package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The Account of the calling session, which is always a Student's: it carries no id and no role. */
public record Account(
        @Schema(example = "Bia Souza")
        String name,

        @Schema(example = "bia@example.com")
        String email,

        @Schema(description = "Whether the Student followed the confirmation link. Nothing waits on it.")
        boolean emailConfirmed) {
}
