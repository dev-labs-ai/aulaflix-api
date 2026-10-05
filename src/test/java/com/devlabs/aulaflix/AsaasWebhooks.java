package com.devlabs.aulaflix;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Asaas's webhook deliveries, posted the way Asaas posts them: JSON, with the token in {@code asaas-access-token} and
 * neither the BFF's key nor a client IP, which the edge blanks.
 */
public final class AsaasWebhooks {

    public static final String TOKEN_HEADER = "asaas-access-token";

    private final MockMvcTester mvc;

    public AsaasWebhooks(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    /** Delivers the body with the right token. */
    public MvcTestResult deliver(String body) {
        return deliver(Asaas.WEBHOOK_TOKEN, body);
    }

    /** Delivers the body with this token, or with none when it is null. */
    public MvcTestResult deliver(String token, String body) {
        MockMvcRequestBuilder request = mvc.post().uri("/v1/webhooks/asaas")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        return request.exchange();
    }

    /** An event id no other delivery of this run has, shaped as Asaas's. */
    public static String newEventId() {
        return "evt_" + UUID.randomUUID().toString().replace("-", "") + "&" + System.nanoTime();
    }

    /** A Checkout event as Asaas's documentation words it: the Checkout's id and status, and no payment. */
    public static String checkoutEvent(String eventId, String event, String checkoutId, String status) {
        return """
                {
                  "id": "%s",
                  "event": "%s",
                  "dateCreated": "2026-10-05 15:45:03",
                  "checkout": {
                    "id": "%s",
                    "status": "%s",
                    "minutesToExpire": 60
                  }
                }""".formatted(eventId, event, checkoutId, status);
    }

    /**
     * A payment event as Asaas words it, its {@code payment} as the charge stood when the event fired. The worker
     * re-reads the charge, so this body only names it.
     */
    public static String paymentEvent(String eventId, String event, String chargeId, String status, int valueCents,
                                      String externalReference) {
        BigDecimal value = BigDecimal.valueOf(valueCents, 2);
        return """
                {
                  "id": "%s",
                  "event": "%s",
                  "dateCreated": "2026-10-05 14:45:03",
                  "payment": {
                    "object": "payment",
                    "id": "%s",
                    "dateCreated": "2026-10-05",
                    "customer": "cus_000000000001",
                    "value": %s,
                    "netValue": %s,
                    "billingType": "PIX",
                    "status": "%s",
                    "externalReference": "%s",
                    "deleted": false
                  }
                }""".formatted(eventId, event, chargeId, value, value, status, externalReference);
    }
}
