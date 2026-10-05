package com.devlabs.aulaflix.service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The Asaas adapter: the few calls the Orders module makes, over Asaas's REST API v3, as a thin {@link RestClient}
 * rather than Asaas's stale SDK. Every failure becomes one of two exceptions: {@link AsaasUnavailableException} when
 * Asaas could not take the call, which a later retry may get through, and {@link AsaasRefusedException} when it
 * refused it, which a retry will not change. Neither message holds a CPF, the key or a URL.
 */
public class AsaasGateway {

    private static final String PIX = "PIX";
    private static final int TOO_MANY_REQUESTS = 429;
    private static final String DONE = "DONE";

    /** Asaas takes an item's name of up to 30 characters. */
    private static final int ITEM_NAME_MAX_CHARACTERS = 30;

    private final RestClient asaas;
    private final Duration retryAfter;

    /**
     * The client's base URL is Asaas's, up to {@code /v3}, and it sends the key; its timeouts bound how long a Student
     * waits. A call Asaas could not take is worth retrying after {@code retryAfter}.
     */
    public AsaasGateway(RestClient asaas, Duration retryAfter) {
        this.asaas = asaas;
        this.retryAfter = retryAfter;
    }

    /** Makes a customer with Asaas's own emails and texts turned off, and answers its id. */
    public String createCustomer(String name, String cpf) {
        return idOf("creating a customer", () -> asaas.post().uri("/customers")
                .body(new NewCustomer(name, cpf, true))
                .retrieve()
                .body(Created.class));
    }

    /**
     * Makes a Pix charge for the customer, due on the day given, under our external reference, and answers its id. The
     * amount is in cents of BRL; Asaas takes reais.
     */
    public String createPixCharge(String customerId, int amountCents, LocalDate dueDate, String externalReference,
                                  String description) {
        return idOf("creating a Pix charge", () -> asaas.post().uri("/payments")
                .body(new NewCharge(customerId, PIX, BigDecimal.valueOf(amountCents, 2), dueDate.toString(),
                        externalReference, description))
                .retrieve()
                .body(Created.class));
    }

    /**
     * Makes a Checkout, Asaas's own page, where the payer pays one item by card, at once or in up to the installments
     * given, until it expires; and answers its id and the link to send the payer to, which Asaas wants used exactly as
     * given. The payer gives Asaas their own details there.
     */
    public Checkout createCardCheckout(CardCheckout checkout) {
        String operation = "creating a Checkout";
        boolean inInstallments = checkout.maxInstallments() > 1;
        Checkout created = call(operation, () -> asaas.post().uri("/checkouts")
                .body(new NewCheckout(List.of("CREDIT_CARD"),
                        inInstallments ? List.of("DETACHED", "INSTALLMENT") : List.of("DETACHED"),
                        Math.toIntExact(checkout.lifetime().toMinutes()), checkout.externalReference(),
                        new Callback(checkout.returnUrl().toString(), checkout.cancelUrl().toString(),
                                checkout.returnUrl().toString()),
                        List.of(new Item(itemName(checkout.item()), checkout.description(), 1,
                                BigDecimal.valueOf(checkout.amountCents(), 2))),
                        inInstallments ? new Installment(checkout.maxInstallments()) : null))
                .retrieve()
                .body(Checkout.class));
        if (created == null || created.id() == null || created.link() == null) {
            throw unreadable(operation);
        }
        return created;
    }

    /** Cancels the Checkout, so that no one pays on it any more. One that is no longer active may be refused. */
    public void cancelCheckout(String checkoutId) {
        call("cancelling a Checkout", () -> asaas.post().uri("/checkouts/{id}/cancel", checkoutId)
                .body(Map.of())
                .retrieve()
                .toBodilessEntity());
    }

    /** The QR code that pays the Pix charge: its PNG in base64, and its copy-and-paste code. */
    public PixQrCode pixQrCode(String chargeId) {
        String operation = "reading a Pix QR code";
        PixQrCode qrCode = call(operation, () -> asaas.get().uri("/payments/{id}/pixQrCode", chargeId)
                .retrieve()
                .body(PixQrCode.class));
        if (qrCode == null || qrCode.encodedImage() == null || qrCode.payload() == null) {
            throw unreadable(operation);
        }
        return qrCode;
    }

    /** The ids of the charges made under the external reference that are not deleted yet. */
    public List<String> chargesUnder(String externalReference) {
        return chargeIds("listing charges", "externalReference", externalReference);
    }

    /** The ids of the charges a Checkout's payer made, one per installment, that are not deleted. */
    public List<String> chargesOfCheckout(String checkoutId) {
        return chargeIds("listing a Checkout's charges", "checkoutSession", checkoutId);
    }

    /**
     * The charge as Asaas holds it now: what a webhook event's body only claims. A charge that is one installment of a
     * card sale comes with its installment plan's total and count, read from Asaas too.
     */
    public Charge charge(String chargeId) {
        String operation = "reading a charge";
        ChargeBody charge = call(operation, () -> asaas.get().uri("/payments/{id}", chargeId)
                .retrieve()
                .body(ChargeBody.class));
        if (charge == null || charge.id() == null || charge.status() == null || charge.value() == null) {
            throw unreadable(operation);
        }
        if (charge.installment() == null) {
            return charge.single();
        }
        String planOperation = "reading an installment plan";
        InstallmentPlan plan = call(planOperation, () -> asaas.get().uri("/installments/{id}", charge.installment())
                .retrieve()
                .body(InstallmentPlan.class));
        if (plan == null || plan.value() == null || plan.installmentCount() == null) {
            throw unreadable(planOperation);
        }
        return charge.inPlan(plan);
    }

    /** Deletes an unpaid charge, so that it can no longer be paid. Deleting is not a refund. */
    public void deleteCharge(String chargeId) {
        call("deleting a charge", () -> asaas.delete().uri("/payments/{id}", chargeId).retrieve().toBodilessEntity());
    }

    /**
     * Refunds the paid charge in full: with no value, Asaas refunds all of it. Asaas taking the call is all it answers;
     * the refund itself is done once the charge's re-read shows it {@code DONE}.
     */
    public void refundCharge(String chargeId) {
        call("refunding a charge", () -> asaas.post().uri("/payments/{id}/refund", chargeId)
                .body(Map.of())
                .retrieve()
                .toBodilessEntity());
    }

    /**
     * Refunds a card sale in installments in full, every installment's charge at once: a refund of one charge would
     * return one installment only. As for a charge, the refund is done once a re-read shows it {@code DONE}.
     */
    public void refundInstallmentPlan(String installmentId) {
        call("refunding an installment plan", () -> asaas.post().uri("/installments/{id}/refund", installmentId)
                .body(Map.of())
                .retrieve()
                .toBodilessEntity());
    }

    private List<String> chargeIds(String operation, String filter, String value) {
        Charges charges = call(operation, () -> asaas.get()
                .uri(uri -> uri.path("/payments").queryParam(filter, value).build())
                .retrieve()
                .body(Charges.class));
        if (charges == null || charges.data() == null) {
            throw unreadable(operation);
        }
        return charges.data().stream().filter(charge -> !charge.deleted()).map(ChargeBody::id)
                .filter(Objects::nonNull).toList();
    }

    private String idOf(String operation, Supplier<Created> request) {
        Created created = call(operation, request);
        if (created == null || created.id() == null) {
            throw unreadable(operation);
        }
        return created.id();
    }

    private <T> T call(String operation, Supplier<T> request) {
        try {
            return request.get();
        } catch (HttpClientErrorException refusal) {
            if (refusal.getStatusCode().value() == TOO_MANY_REQUESTS) {
                throw new AsaasUnavailableException(operation, "HTTP " + TOO_MANY_REQUESTS, retryAfter);
            }
            throw new AsaasRefusedException(operation, refusal.getStatusCode().value(), errorsOf(refusal));
        } catch (HttpServerErrorException failure) {
            throw new AsaasUnavailableException(operation, "HTTP " + failure.getStatusCode().value(), retryAfter);
        } catch (ResourceAccessException failure) {
            throw new AsaasUnavailableException(operation, failure.getMostSpecificCause().toString(), retryAfter);
        } catch (RestClientException unreadable) {
            throw unreadable(operation);
        }
    }

    /** The item's name as Asaas takes it: cut, with an ellipsis, when it is longer. */
    private static String itemName(String name) {
        return name.length() <= ITEM_NAME_MAX_CHARACTERS ? name
                : name.substring(0, ITEM_NAME_MAX_CHARACTERS - 1) + "…";
    }

    /** Asaas words each refusal as {@code {"errors": [{"code", "description"}]}}. */
    private static List<AsaasError> errorsOf(RestClientResponseException refusal) {
        try {
            Errors errors = refusal.getResponseBodyAs(Errors.class);
            return errors == null || errors.errors() == null ? List.of()
                    : errors.errors().stream().filter(error -> error.code() != null).toList();
        } catch (RestClientException unreadable) {
            return List.of();
        }
    }

    private static AsaasRefusedException unreadable(String operation) {
        return new AsaasRefusedException(operation, "an answer the API cannot read");
    }

    /** A Pix charge's QR code: {@code encodedImage} is the PNG in base64, {@code payload} the copy-and-paste code. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PixQrCode(String encodedImage, String payload) {
    }

    /**
     * A card Checkout to make: one item, its name and description, for the amount in cents of BRL, in up to the
     * installments given, one of which is a single payment; the external reference, an Order's code; how long it
     * lives; and where Asaas sends the payer back, when they paid or it expired, and when they cancelled.
     */
    public record CardCheckout(String externalReference, String item, String description, int amountCents,
                               int maxInstallments, Duration lifetime, URI returnUrl, URI cancelUrl) {
    }

    /** A Checkout Asaas made: its id, which its charges carry as {@code checkoutSession}, and its link. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Checkout(String id, String link) {
    }

    private record NewCustomer(String name, String cpfCnpj, boolean notificationDisabled) {
    }

    private record NewCharge(String customer, String billingType, BigDecimal value, String dueDate,
                             String externalReference, String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record NewCheckout(List<String> billingTypes, List<String> chargeTypes, int minutesToExpire,
                               String externalReference, Callback callback, List<Item> items,
                               Installment installment) {
    }

    private record Callback(String successUrl, String cancelUrl, String expiredUrl) {
    }

    private record Item(String name, String description, int quantity, BigDecimal value) {
    }

    private record Installment(int maxInstallmentCount) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Created(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Charges(List<ChargeBody> data) {
    }

    /** A charge as Asaas words it; an installment of a card sale names its plan in {@code installment}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ChargeBody(String id, String status, BigDecimal value, String externalReference, boolean deleted,
                              String checkoutSession, String installment, List<Refund> refunds) {

        Charge single() {
            return new Charge(id, status, value, externalReference, deleted, checkoutSession, null, 1, refunds);
        }

        Charge inPlan(InstallmentPlan plan) {
            return new Charge(id, status, plan.value(), externalReference, deleted, checkoutSession, installment,
                    plan.installmentCount(), refunds);
        }
    }

    /** An installment plan: the whole sale's {@code value} in reais, and its {@code installmentCount}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record InstallmentPlan(BigDecimal value, Integer installmentCount) {
    }

    /**
     * A charge: its {@code status}, Asaas's own ({@code PENDING}, {@code CONFIRMED}, {@code RECEIVED},
     * {@code AWAITING_RISK_ANALYSIS}, …); the {@code value} in reais of the sale it is part of, which is its own unless
     * it is one of a card sale's {@code installments}, then its {@code installment} plan's whole; the
     * {@code externalReference} it was made under, an Order's code, which a Checkout's charges may lack; whether it was
     * deleted; the {@code checkoutSession}, the Checkout its payer paid on, if any; and its {@code refunds}.
     */
    public record Charge(String id, String status, BigDecimal value, String externalReference, boolean deleted,
                         String checkoutSession, String installment, int installments, List<Refund> refunds) {

        /** A card payment held for Asaas's manual risk analysis, which neither pays nor declines it yet. */
        public boolean awaitingRiskAnalysis() {
            return "AWAITING_RISK_ANALYSIS".equals(status);
        }

        /** Whether a refund of the charge is done: until then, the money has not left. */
        public boolean refundDone() {
            return refunds != null && refunds.stream().anyMatch(refund -> DONE.equals(refund.status()));
        }
    }

    /**
     * One of a charge's refunds, in its own {@code status}: {@code PENDING}, {@code DONE}, {@code CANCELLED}, or
     * awaiting an authorization.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Refund(String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Errors(List<AsaasError> errors) {
    }
}
