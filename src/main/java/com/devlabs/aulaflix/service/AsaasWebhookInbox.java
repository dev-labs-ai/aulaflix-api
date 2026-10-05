package com.devlabs.aulaflix.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.devlabs.aulaflix.domain.entity.WebhookEventState;
import com.devlabs.aulaflix.exception.WebhookBodyTooLargeException;
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

    /** 256 KB: the edge's own limit, so that a body the edge lets through is never refused here. */
    private static final int MAX_BODY_BYTES = 256 * 1024;

    /** The events whose charge the worker re-reads; any other is stored as ignored. */
    private static final Set<String> HANDLED_EVENTS = Set.of("PAYMENT_CONFIRMED", "PAYMENT_RECEIVED");

    private static final int MAX_EVENT_ID_LENGTH = 255;
    private static final int MAX_CHARGE_ID_LENGTH = 64;

    private final WebhookEventRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    public AsaasWebhookInbox(WebhookEventRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Reads the body, up to {@link #MAX_BODY_BYTES}, and stores it with the event's id and type, and the charge it
     * names when it is an event the worker handles. A body that is not an event is stored too, without an id, and
     * logged: something is sending Asaas's token what Asaas never sends.
     */
    public void receive(InputStream body) {
        byte[] raw = readAtMost(body);
        Delivery delivery = read(raw);
        int stored = repository.insertUnlessReceived(delivery.eventId(), delivery.eventType(), delivery.chargeId(),
                raw, clock.instant().truncatedTo(ChronoUnit.MICROS), delivery.state().name());
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

    private static byte[] readAtMost(InputStream body) {
        try {
            byte[] raw = body.readNBytes(MAX_BODY_BYTES + 1);
            if (raw.length > MAX_BODY_BYTES) {
                throw new WebhookBodyTooLargeException();
            }
            return raw;
        } catch (IOException failure) {
            throw new UncheckedIOException("Reading an Asaas webhook's body failed", failure);
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
        if (!HANDLED_EVENTS.contains(eventType)) {
            return new Delivery(eventId, eventType, null, WebhookEventState.IGNORED, null);
        }
        String chargeId = textOf(event.path("payment").path("id"));
        if (chargeId == null || chargeId.length() > MAX_CHARGE_ID_LENGTH) {
            return Delivery.unprocessable(eventId, eventType, "no charge id in " + eventType);
        }
        return new Delivery(eventId, eventType, chargeId, WebhookEventState.PENDING, null);
    }

    /** A JSON string's text, or null for anything else, an empty string included. */
    private static String textOf(JsonNode node) {
        return node.isString() && !node.stringValue().isEmpty() ? node.stringValue() : null;
    }

    /** What the inbox makes of a delivery, and, when it cannot process it, why. */
    private record Delivery(String eventId, String eventType, String chargeId, WebhookEventState state,
                            String problem) {

        static Delivery unprocessable(String eventId, String eventType, String problem) {
            return new Delivery(eventId, eventType, null, WebhookEventState.UNPROCESSABLE, problem);
        }
    }
}
