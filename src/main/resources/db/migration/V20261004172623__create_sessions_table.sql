CREATE SEQUENCE seq_session INCREMENT BY 50;

-- The token itself is never stored: only its SHA-256, as lower-case hex.
CREATE TABLE sessions (
    id           BIGINT      PRIMARY KEY,
    token_hash   VARCHAR(64) NOT NULL,
    account_id   BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_sessions_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_sessions_account FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

CREATE INDEX idx_sessions_account_id ON sessions (account_id);
