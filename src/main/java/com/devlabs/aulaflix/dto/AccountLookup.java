package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record AccountLookup(
        @Schema(description = "Whether an Account has the email: the web then asks for the password, or offers to "
                + "sign up.")
        boolean exists) {
}
