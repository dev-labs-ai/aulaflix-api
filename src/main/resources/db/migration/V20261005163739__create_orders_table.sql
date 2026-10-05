-- The Student's one Asaas customer, made with the CPF of their first Pix; the CPF itself is never stored. Nullable, so
-- no existing row needs a value.
ALTER TABLE accounts ADD COLUMN asaas_customer_id VARCHAR(64);

CREATE SEQUENCE seq_order INCREMENT BY 50;

-- A Student's request to buy one Course by one payment method. The prices are a snapshot taken when it was placed,
-- since the Course's may change. The code is what the Student quotes, and Asaas's externalReference. The Order is
-- written before Asaas is called, so the charge's id and the Pix QR code arrive afterwards, and stay empty when Asaas
-- failed and the Order was cancelled.
CREATE TABLE orders (
    id                    BIGINT       PRIMARY KEY,
    code                  VARCHAR(8)   NOT NULL,
    student_id            BIGINT       NOT NULL REFERENCES accounts (id),
    course_id             BIGINT       NOT NULL REFERENCES courses (id),
    method                VARCHAR(8)   NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    list_price_cents      INTEGER      NOT NULL,
    pix_discount_percent  INTEGER      NOT NULL,
    amount_cents          INTEGER      NOT NULL,
    asaas_payment_id      VARCHAR(64),
    pix_qr_code_png       TEXT,
    pix_copy_paste_code   TEXT,
    expires_at            TIMESTAMPTZ  NOT NULL,
    duplicate_payment     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_orders_code UNIQUE (code),
    CONSTRAINT ck_orders_method CHECK (method IN ('PIX', 'CARD')),
    CONSTRAINT ck_orders_status CHECK (status IN ('AWAITING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED', 'DECLINED',
                                                  'REFUNDING', 'REFUNDED', 'REVERSED')),
    CONSTRAINT ck_orders_amounts CHECK (amount_cents BETWEEN 0 AND list_price_cents
                                        AND pix_discount_percent BETWEEN 0 AND 99)
);

-- At most one Order awaiting payment per Student and Course: asking again returns it instead of charging twice
CREATE UNIQUE INDEX uk_orders_awaiting_student_course ON orders (student_id, course_id)
    WHERE status = 'AWAITING_PAYMENT';
-- The Student's list, newest first
CREATE INDEX idx_orders_student_created_at ON orders (student_id, created_at DESC, id DESC);
CREATE INDEX idx_orders_course_id ON orders (course_id);
