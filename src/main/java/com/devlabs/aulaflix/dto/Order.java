package com.devlabs.aulaflix.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.PaymentMethod;

/**
 * A Student's Order, as the Student sees it: the list price and the discount stay the Admin's. {@code pix} appears only
 * while the Order awaits payment, and only on the answer to placing it and on its own read.
 */
public record Order(
        @Schema(description = "8 unambiguous characters, which the Student quotes (\"Pedido nº K7M2Q9XA\")",
                example = "K7M2Q9XA")
        String code,

        OrderStatus status,

        PaymentMethod method,

        OrderedCourse course,

        @Schema(description = "What the Student pays, in cents of BRL: the Pix price for a Pix", example = "44730")
        int amountCents,

        Instant createdAt,

        @Schema(description = "Paid while the Student already had the Course; an Admin refunds it")
        boolean duplicatePayment,

        PixPayment pix) {
}
