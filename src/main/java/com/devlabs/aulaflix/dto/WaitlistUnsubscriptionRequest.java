package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/** The token of a launch email's unsubscribe links, which the web posts from its page or from the one-click URL. */
public record WaitlistUnsubscriptionRequest(
        @NotBlank(message = "required")
        @Schema(description = """
                What follows `#` in `{webBase}/cancelar-aviso#<token>`, or `token=` in the `List-Unsubscribe` URL.""")
        String token) {
}
