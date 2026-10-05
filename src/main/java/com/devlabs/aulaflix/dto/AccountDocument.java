package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a Student may edit of their Account, put back whole: only the name, since the email never changes. Only a
 * missing name is refused here; the service checks it once normalized. The violation message is the API's field code.
 */
public record AccountDocument(
        @NotNull(message = "required")
        @Schema(description = "Trimmed, with inner whitespace collapsed; 1 to 80 characters.", example = "Bia Souza")
        String name) {
}
