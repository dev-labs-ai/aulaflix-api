CREATE SEQUENCE seq_email_confirmation_token INCREMENT BY 50;

-- The token in a confirmation link is never stored: only its SHA-256, as lower-case hex. A used token is kept until it
-- expires, so that a second click still says confirmed; resend tells the links a Student asked for again from the one
-- sign-up sent, since only those count against the daily cap.
CREATE TABLE email_confirmation_tokens (
    id         BIGINT      PRIMARY KEY,
    token_hash VARCHAR(64) NOT NULL,
    account_id BIGINT      NOT NULL,
    resend     BOOLEAN     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    voided_at  TIMESTAMPTZ,
    CONSTRAINT uk_email_confirmation_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_email_confirmation_tokens_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

-- A resend reads the Account's latest links: the cooldown and the daily cap.
CREATE INDEX idx_email_confirmation_tokens_account_created ON email_confirmation_tokens (account_id, created_at);
