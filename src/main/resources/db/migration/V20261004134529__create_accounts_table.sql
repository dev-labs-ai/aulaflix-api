CREATE SEQUENCE seq_account INCREMENT BY 50;

CREATE TABLE accounts (
    id            BIGINT       PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    name          VARCHAR(80)  NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_accounts_email UNIQUE (email),
    CONSTRAINT ck_accounts_role CHECK (role IN ('STUDENT', 'ADMIN'))
);
