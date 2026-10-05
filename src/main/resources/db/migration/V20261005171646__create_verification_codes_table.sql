CREATE SEQUENCE seq_verification_code INCREMENT BY 50;

-- The 6-digit codes that reset a forgotten password (RESET) or change it while signed in (CHANGE). A code is never
-- stored: only its HMAC-SHA256 under the server's secret aulaflix.codes.hmac-key, as lower-case hex, since a plain hash
-- of a million values reverses offline. The HMAC also covers the Account and the kind, so a code proves nothing for
-- another Account or the other flow. failed_attempts counts the wrong tries; the fifth voids the code.
CREATE TABLE verification_codes (
    id              BIGINT      PRIMARY KEY,
    account_id      BIGINT      NOT NULL,
    kind            VARCHAR(16) NOT NULL,
    code_hmac       VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    failed_attempts INTEGER     NOT NULL,
    used_at         TIMESTAMPTZ,
    voided_at       TIMESTAMPTZ,
    CONSTRAINT ck_verification_codes_kind CHECK (kind IN ('RESET', 'CHANGE')),
    CONSTRAINT ck_verification_codes_failed_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT fk_verification_codes_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE CASCADE
);

-- Issuing and redeeming read the Account's latest codes: the cooldown, the daily cap and the one code still alive.
CREATE INDEX idx_verification_codes_account_created ON verification_codes (account_id, created_at);
