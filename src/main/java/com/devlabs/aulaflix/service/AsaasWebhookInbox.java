package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.devlabs.aulaflix.domain.entity.WebhookEventState;
import com.devlabs.aulaflix.repository.WebhookEventRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The webhook inbox: every delivery Asaas makes with the right token is stored as it arrived and answered at once,
 * whatever it holds, since only a 200 stops Asaas from delivering it again. What it claims is acted on later, by the
 * {@link WebhookWorker}, and only once a re-read of the charge confirms it. A repeated event id stores nothing.
 */
@Service
public class AsaasWebhookInbox {

    private static final Logger log = LoggerFactory.getLogger(AsaasWebhookInbox.class);

    /**
     * The events whose charge the worker re-reads: a payment, a card's risk analysis, and money going back by a
     * refund, whoever made it, or a chargeback, at any step of its dispute. Any other is stored as ignored.
     */
    private static final Set<String> HANDLED_EVENTS = Set.of("PAYMENT_CONFIRMED", "PAYMENT_RECEIVED",
            WebhookWorker.RISK_ANALYSIS_REJECTION, "PAYMENT_APPROVED_BY_RISK_ANALYSIS",
            "PAYMENT_REFUNDED", "PAYMENT_PARTIALLY_REFUNDED", "PAYMENT_REFUND_IN_PROGRESS",
            "PAYMENT_CHARGEBACK_REQUESTED", "PAYMENT_CHARGEBACK_DISPUTE", "PAYMENT_AWAITING_CHARGEBACK_REVERSAL");

    /** The events whose Checkout's Order the worker expires, once it re-read the Checkout's charges. */
    private static final Set<String> HANDLED_CHECKOUT_EVENTS = Set.of("CHECKOUT_EXPIRED");

    private static final int MAX_EVENT_ID_LENGTH = 255;
    private static final int MAX_CHARGE_ID_LENGTH = 64;
    private static final int MAX_CHECKOUT_ID_LENGTH = 64;

    private final WebhookEventRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    public AsaasWebhookInbox(WebhookEventRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Stores the body, as it arrived, with the event's id and type, and the charge it names when it is an event the
     * worker handles. A body that is not an event is stored too, without an id, and logged: something is sending
     * Asaas's token what Asaas never sends.
     */
    public void receive(byte[] raw) {
        Delivery delivery = read(raw);
        int stored = repository.insertUnlessReceived(delivery.eventId(), delivery.eventType(), delivery.chargeId(),
                delivery.checkoutId(), raw, clock.instant().truncatedTo(ChronoUnit.MICROS), delivery.state().name());
        if (stored == 0) {
            log.info("Asaas webhook event {} was received before", delivery.eventId());
        } else if (delivery.state() == WebhookEventState.UNPROCESSABLE) {
            log.warn("Stored an Asaas webhook delivery the API cannot process ({} bytes): {}", raw.length,
                    delivery.problem());
        } else {
            log.info("Stored Asaas webhook event {} ({}) as {}", delivery.eventId(), delivery.eventType(),
                    delivery.state());
        }
    }

    private Delivery read(byte[] raw) {
        JsonNode event;
        try {
            event = json.readTree(raw);
        } catch (JacksonException notJson) {
            return Delivery.unprocessable(null, null, "not JSON");
        }
        String eventId = textOf(event.path("id"));
        if (eventId == null || eventId.length() > MAX_EVENT_ID_LENGTH) {
            return Delivery.unprocessable(null, null, "no event id");
        }
        String eventType = textOf(event.path("event"));
        if (HANDLED_CHECKOUT_EVENTS.contains(eventType)) {
            return checkoutEvent(eventId, eventType, event);
        }
        if (!HANDLED_EVENTS.contains(eventType)) {
            return new Delivery(eventId, eventType, null, null, WebhookEventState.IGNORED, null);
        }
        return paymentEvent(eventId, eventType, event);
    }

    /** A Checkout event the worker handles, pending when it names its Checkout. */
    private static Delivery checkoutEvent(String eventId, String eventType, JsonNode event) {
        String checkoutId = textOf(event.path("checkout").path("id"));
        if (checkoutId == null || checkoutId.length() > MAX_CHECKOUT_ID_LENGTH) {
            return Delivery.unprocessable(eventId, eventType, "no Checkout id in " + eventType);
        }
        return new Delivery(eventId, eventType, null, checkoutId, WebhookEventState.PENDING, null);
    }

    /** A payment event the worker handles, pending when it names its charge. */
    private static Delivery paymentEvent(String eventId, String eventType, JsonNode event) {
        String chargeId = textOf(event.path("payment").path("id"));
        if (chargeId == null || chargeId.length() > MAX_CHARGE_ID_LENGTH) {
            return Delivery.unprocessable(eventId, eventType, "no charge id in " + eventType);
        }
        return new Delivery(eventId, eventType, chargeId, null, WebhookEventState.PENDING, null);
    }

    /** A JSON string's text, or null for anything else, an empty string included. */
    private static String textOf(JsonNode node) {
        return node.isString() && !node.stringValue().isEmpty() ? node.stringValue() : null;
    }

    /** What the inbox makes of a delivery, and, when it cannot process it, why. */
    private record Delivery(String eventId, String eventType, String chargeId, String checkoutId,
                            WebhookEventState state, String problem) {

        static Delivery unprocessable(String eventId, String eventType, String problem) {
            return new Delivery(eventId, eventType, null, null, WebhookEventState.UNPROCESSABLE, problem);
        }
    }
}
