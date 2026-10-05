package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** An On sale Course's prices, in cents of BRL, worked out by the API so that the web only formats them. */
public record CoursePricing(
        @Schema(description = "The full price, which a card pays", example = "49700")
        int priceCents,

        @Schema(description = "The Pix discount, in whole percent; 0 when there is none", example = "10")
        int pixDiscountPercent,

        @Schema(description = "The price minus the Pix discount, rounded down to the cent", example = "44730")
        int pixPriceCents,

        @Schema(description = "The most interest-free card installments", example = "10")
        int maxInstallments,

        @Schema(description = "Each installment at the most installments: the price divides by them exactly",
                example = "4970")
        int installmentCents) {
}
