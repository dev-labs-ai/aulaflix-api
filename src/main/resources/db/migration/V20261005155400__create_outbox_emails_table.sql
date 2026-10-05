CREATE SEQUENCE seq_outbox_email INCREMENT BY 50;

-- Each email the API sends, rendered when queued in the transaction of what it tells of, so that the drainer only
-- sends it. A failed attempt moves next_attempt_at on; a pending email is never given up on.
CREATE TABLE outbox_emails (
    id              BIGINT       PRIMARY KEY,
    template        VARCHAR(40)  NOT NULL,
    recipient       VARCHAR(254) NOT NULL,
    subject         VARCHAR(255) NOT NULL,
    body            TEXT         NOT NULL,
    headers         JSONB        NOT NULL,
    state           VARCHAR(16)  NOT NULL,
    attempts        INTEGER      NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT ck_outbox_emails_state CHECK (state IN ('PENDING', 'SENT')),
    CONSTRAINT ck_outbox_emails_attempts CHECK (attempts >= 0)
);

-- The drainer's queue: only the emails still to send, the next one due first.
CREATE INDEX idx_outbox_emails_pending ON outbox_emails (next_attempt_at, id) WHERE state = 'PENDING';
