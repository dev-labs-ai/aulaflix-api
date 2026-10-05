package com.devlabs.aulaflix.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
    private static final String CANCELLED = "CANCELLED";
    private static final String REFUNDED = "REFUNDED";
    private static final Set<String> REFUND_STATUSES = Set.of("REFUND_REQUESTED", "REFUND_IN_PROGRESS");
    private static final Set<String> CHARGEBACK_STATUSES = Set.of("CHARGEBACK_REQUESTED", "CHARGEBACK_DISPUTE",
            "AWAITING_CHARGEBACK_REVERSAL");

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
        String operation = "listing charges";
        Charges charges = call(operation, () -> asaas.get()
                .uri(uri -> uri.path("/payments").queryParam("externalReference", externalReference).build())
                .retrieve()
                .body(Charges.class));
        if (charges == null || charges.data() == null) {
            throw unreadable(operation);
        }
        return charges.data().stream().filter(charge -> !charge.deleted()).map(Charge::id)
                .filter(Objects::nonNull).toList();
    }

    /** The charge as Asaas holds it now: what a webhook event's body only claims. */
    public Charge charge(String chargeId) {
        String operation = "reading a charge";
        Charge charge = call(operation, () -> asaas.get().uri("/payments/{id}", chargeId)
                .retrieve()
                .body(Charge.class));
        if (charge == null || charge.id() == null || charge.status() == null || charge.value() == null) {
            throw unreadable(operation);
        }
        return charge;
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

    private record NewCustomer(String name, String cpfCnpj, boolean notificationDisabled) {
    }

    private record NewCharge(String customer, String billingType, BigDecimal value, String dueDate,
                             String externalReference, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Created(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Charges(List<Charge> data) {
    }

    /**
     * A charge: its {@code status}, Asaas's own ({@code PENDING}, {@code CONFIRMED}, {@code RECEIVED}, …); its
     * {@code value} in reais; the {@code externalReference} it was made under, an Order's code; whether it was
     * deleted; its refunds; and its chargeback, once one was opened.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Charge(String id, String status, BigDecimal value, String externalReference, boolean deleted,
                         List<Refund> refunds, Chargeback chargeback) {

        /**
         * Whether money went back to the payer, or is going: a refund, whoever asked for it; a chargeback; or the
         * charge refunded with no refund of its own, as Asaas shows a Pix whose cautionary block was upheld.
         */
        public boolean moneyBack() {
            return chargedBack() || refundRequested() || REFUNDED.equals(status);
        }

        /**
         * Whether a chargeback was opened: from its request, through its dispute, even one won, until the money comes
         * back; or the charge was refunded with a chargeback, as a lost dispute leaves it.
         */
        public boolean chargedBack() {
            return CHARGEBACK_STATUSES.contains(status) || REFUNDED.equals(status) && chargeback != null;
        }

        /** Whether a refund was asked of Asaas, by anyone: the charge says so, or lists one Asaas did not cancel. */
        public boolean refundRequested() {
            return REFUND_STATUSES.contains(status) || liveRefunds().findAny().isPresent();
        }

        /**
         * Whether a refund of the charge is done: until then, the money has not left. A charge refunded with no refund
         * listed has left too.
         */
        public boolean refundDone() {
            return liveRefunds().anyMatch(refund -> DONE.equals(refund.status()))
                    || REFUNDED.equals(status) && liveRefunds().findAny().isEmpty();
        }

        private Stream<Refund> liveRefunds() {
            return refunds == null ? Stream.empty()
                    : refunds.stream().filter(refund -> !CANCELLED.equals(refund.status()));
        }
    }

    /** A charge's chargeback, in its own {@code status}: {@code REQUESTED}, {@code IN_DISPUTE}, … */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chargeback(String status) {
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
