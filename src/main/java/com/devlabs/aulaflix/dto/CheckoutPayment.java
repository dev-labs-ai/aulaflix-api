package com.devlabs.aulaflix.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** Where the Student pays a card Order: Asaas's own page, a Checkout, until it expires (ADR 0006). */
public record CheckoutPayment(
        @Schema(description = """
                The Checkout's link, exactly as Asaas gave it, to send the browser to. Coming back to AulaFlix from it \
                proves no payment: read the Order.""",
                example = "https://asaas.com/checkoutSession/show?id=2bd251f0-09b2-44ff-8a0c-a5cb29e5bbda")
        String url,

        @Schema(description = "When the Order expires, 60 minutes after it was placed")
        Instant expiresAt) {
}
