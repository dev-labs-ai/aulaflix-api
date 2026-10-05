package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/** The token from the link's fragment, which the web's page posts on load. */
public record EmailConfirmationRequest(
        @NotBlank(message = "required")
        @Schema(description = "What follows `#` in `{webBase}/confirmar-email#<token>`.",
                example = "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s")
        String token) {
}
