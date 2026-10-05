package com.devlabs.aulaflix.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One delivery of Asaas's webhook, as it arrived. Rows are only ever inserted by the inbox's own statement, which
 * skips an event id already stored, so this entity reads and settles them, and never persists one.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEventEntity {

    @Id
    private Long id;

    @Column(name = "asaas_event_id", length = 255, updatable = false)
    private String asaasEventId;

    @Column(name = "event_type", updatable = false)
    private String eventType;

    @Column(name = "charge_id", length = 64, updatable = false)
    private String chargeId;

    @Column(nullable = false, updatable = false)
    private byte[] body;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WebhookEventState state;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected WebhookEventEntity() {
    }

    /** Settled once and for all, by the worker. */
    public void settle(WebhookEventState outcome, Instant at) {
        if (state != WebhookEventState.PENDING || outcome == WebhookEventState.PENDING) {
            throw new IllegalStateException("Webhook event %d is %s, and cannot become %s".formatted(id, state,
                    outcome));
        }
        this.state = outcome;
        this.settledAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getAsaasEventId() {
        return asaasEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getChargeId() {
        return chargeId;
    }

    public WebhookEventState getState() {
        return state;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getSettledAt() {
        return settledAt;
    }
}
