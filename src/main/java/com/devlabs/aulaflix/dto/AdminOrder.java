package com.devlabs.aulaflix.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.PaymentMethod;

/**
 * An Order as Admins see it, in any state: the Student's shape, plus what deciding and auditing a refund takes. A
 * field that does not fit its state is left out.
 */
public record AdminOrder(
        @Schema(description = "8 unambiguous characters, which the Student quotes", example = "K7M2Q9XA")
        String code,

        OrderStatus status,

        PaymentMethod method,

        OrderedCourse course,

        @Schema(description = "What the Student pays, in cents of BRL: the Pix price for a Pix", example = "44730")
        int amountCents,

        Instant createdAt,

        @Schema(description = "When it was paid; left out until then")
        Instant paidAt,

        @Schema(description = "Paid while the Student already had the Course; it granted nothing, and is refunded")
        boolean duplicatePayment,

        AccountSummary student,

        @Schema(description = "The Course's price when the Order was placed, in cents of BRL", example = "49700")
        int listPriceCents,

        @Schema(description = "The Course's Pix discount when the Order was placed, in whole percent", example = "10")
        int pixDiscountPercent,

        @Schema(description = """
                Whole days since it was paid, by the API's clock: what the 7-day guarantee is judged by. Left out \
                until it is paid""", example = "3")
        Long daysSincePayment,

        @Schema(description = "The Enrollment its payment granted; left out when it granted none")
        GrantedEnrollment enrollment,

        @Schema(description = "The id of its Asaas charge; left out until Asaas gave one", example = "pay_080225913252")
        String asaasChargeId,

        @Schema(description = "How many installments the Student chose on Asaas's page; left out until a card pays",
                example = "10")
        Integer installments,

        @Schema(description = "The id of a card Order's Asaas Checkout; left out for a Pix, and until Asaas gave one",
                example = "2bd251f0-09b2-44ff-8a0c-a5cb29e5bbda")
        String asaasCheckoutId,

        @Schema(description = """
                The id of the Asaas installment plan a card paid in installments makes, through which it is refunded; \
                left out otherwise""", example = "ins_000005219613")
        String asaasInstallmentId,

        @Schema(description = "When its refund was asked of Asaas; left out until then")
        Instant refundRequestedAt,

        @Schema(description = "The Admin who asked for its refund; left out for a refund made in the Asaas UI")
        AccountSummary refundRequestedBy,

        @Schema(description = "When Asaas reported its refund done; left out until then")
        Instant refundedAt) {
}
