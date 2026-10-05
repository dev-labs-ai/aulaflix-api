package com.devlabs.aulaflix.domain.entity;

import java.time.Instant;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_emails")
public class OutboxEmailEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "seq_outbox_email")
    @SequenceGenerator(name = "seq_outbox_email", sequenceName = "seq_outbox_email", allocationSize = 50)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private EmailTemplate template;

    @Column(nullable = false, length = 254, updatable = false)
    private String recipient;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(nullable = false, updatable = false)
    private String body;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Map<String, String> headers;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxEmailState state;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEmailEntity() {
    }

    /** A new email, due at once. */
    public OutboxEmailEntity(EmailTemplate template, String recipient, String subject, String body,
                             Map<String, String> headers, Instant createdAt) {
        this.template = template;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.headers = Map.copyOf(headers);
        this.state = OutboxEmailState.PENDING;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public EmailTemplate getTemplate() {
        return template;
    }

    public String getRecipient() {
        return recipient;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public OutboxEmailState getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void recordSent(Instant at) {
        attempts++;
        state = OutboxEmailState.SENT;
        sentAt = at;
    }

    public void recordFailure(Instant nextAttemptAt) {
        attempts++;
        this.nextAttemptAt = nextAttemptAt;
    }
}
