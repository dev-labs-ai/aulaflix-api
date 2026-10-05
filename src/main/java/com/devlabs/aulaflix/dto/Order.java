package com.devlabs.aulaflix.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.PaymentMethod;

/**
 * A Student's Order, as the Student sees it: the list price and the discount stay the Admin's. A Pix Order carries
 * {@code pix}, a card Order {@code checkout}; either appears only while the Order awaits payment, and only on the answer
 * to placing it and on its own read.
 */
public record Order(
        @Schema(description = "8 unambiguous characters, which the Student quotes (\"Pedido nº K7M2Q9XA\")",
                example = "K7M2Q9XA")
        String code,

        OrderStatus status,

        PaymentMethod method,

        OrderedCourse course,

        @Schema(description = "What the Student pays, in cents of BRL: the Pix price for a Pix, the price for a card",
                example = "44730")
        int amountCents,

        Instant createdAt,

        @Schema(description = "When it was paid; left out until then")
        Instant paidAt,

        @Schema(description = "Paid while the Student already had the Course; an Admin refunds it")
        boolean duplicatePayment,

        @Schema(description = "How many installments the Student chose on Asaas's page; left out until a card pays",
                example = "10")
        Integer installments,

        PixPayment pix,

        CheckoutPayment checkout) {
}
