-- When the Order was paid: when its charge reached CONFIRMED or RECEIVED, whichever came first. Nullable, since an
-- Order awaiting payment has none.
ALTER TABLE orders ADD COLUMN paid_at TIMESTAMPTZ;
-- The webhook worker finds the Order of the charge it re-read
CREATE INDEX idx_orders_asaas_payment_id ON orders (asaas_payment_id);

-- The Order whose payment granted the Enrollment. A manual Enrollment has none. No deployed code writes an
-- Order-origin row yet, so the check holds for every existing row and needs no backfill.
ALTER TABLE enrollments ADD COLUMN order_id BIGINT REFERENCES orders (id);
ALTER TABLE enrollments ADD CONSTRAINT ck_enrollments_order_grant
    CHECK (origin <> 'ORDER' OR order_id IS NOT NULL);
-- One Enrollment per Order, however many times Asaas confirms its payment; manual ones, without an Order, are distinct
CREATE UNIQUE INDEX uk_enrollments_order_id ON enrollments (order_id);

CREATE SEQUENCE seq_webhook_event INCREMENT BY 50;

-- Every delivery of Asaas's webhook that carried the right token, kept as it arrived: the body as raw bytes, since one
-- that is not an event is kept too. Asaas's event id makes a redelivery a no-op; a body without one has none, and
-- PostgreSQL keeps any number of NULLs under the unique constraint. The worker re-reads the charge of each pending
-- event, then settles it: processed, ignored (nothing to do), or unprocessable (the re-read refused or contradicted).
CREATE TABLE webhook_events (
    id              BIGINT        PRIMARY KEY,
    asaas_event_id  VARCHAR(255),
    event_type      TEXT,
    charge_id       VARCHAR(64),
    body            BYTEA         NOT NULL,
    received_at     TIMESTAMPTZ   NOT NULL,
    state           VARCHAR(16)   NOT NULL,
    settled_at      TIMESTAMPTZ,
    CONSTRAINT uk_webhook_events_asaas_event_id UNIQUE (asaas_event_id),
    CONSTRAINT ck_webhook_events_state CHECK (state IN ('PENDING', 'PROCESSED', 'IGNORED', 'UNPROCESSABLE')),
    CONSTRAINT ck_webhook_events_pending_charge CHECK (state <> 'PENDING' OR charge_id IS NOT NULL)
);

-- The worker's queue: only the events still to process, oldest first
CREATE INDEX idx_webhook_events_pending ON webhook_events (id) WHERE state = 'PENDING';
