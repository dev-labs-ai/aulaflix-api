CREATE SEQUENCE seq_course INCREMENT BY 50;

-- The ordered plain-text lists belong to their Course and are only ever read and written whole, so each is a JSON
-- array on the row: of strings, and of {question, answer} objects for the FAQ.
CREATE TABLE courses (
    id                   BIGINT       PRIMARY KEY,
    slug                 VARCHAR(80)  NOT NULL,
    title                VARCHAR(120) NOT NULL,
    summary              VARCHAR(300),
    area                 VARCHAR(16),
    icon                 VARCHAR(16),
    tone                 VARCHAR(16),
    about                JSONB        NOT NULL,
    learn                JSONB        NOT NULL,
    audience             JSONB        NOT NULL,
    planned_topics       JSONB        NOT NULL,
    faq                  JSONB        NOT NULL,
    price_cents          INTEGER,
    pix_discount_percent INTEGER,
    max_installments     INTEGER,
    status               VARCHAR(16)  NOT NULL,
    coming_soon_at       TIMESTAMPTZ,
    on_sale_at           TIMESTAMPTZ,
    CONSTRAINT uk_courses_slug UNIQUE (slug),
    CONSTRAINT ck_courses_area
        CHECK (area IN ('BACKEND', 'FRONTEND', 'DATABASES', 'DEVOPS', 'AI', 'QUALITY', 'ARCHITECTURE')),
    CONSTRAINT ck_courses_icon
        CHECK (icon IN ('SERVER', 'APP_WINDOW', 'DATABASE', 'CONTAINER', 'BOT', 'FLASK_CONICAL', 'BLOCKS')),
    CONSTRAINT ck_courses_tone CHECK (tone IN ('CORAL', 'YELLOW', 'SAGE')),
    CONSTRAINT ck_courses_status CHECK (status IN ('DRAFT', 'COMING_SOON', 'ON_SALE')),
    CONSTRAINT ck_courses_price_cents CHECK (price_cents >= 1),
    CONSTRAINT ck_courses_pix_discount_percent CHECK (pix_discount_percent BETWEEN 0 AND 99),
    CONSTRAINT ck_courses_max_installments CHECK (max_installments BETWEEN 1 AND 12),
    -- Every installment is exact: the advertised "10x de R$ X" is what Asaas charges
    CONSTRAINT ck_courses_installments_exact CHECK (price_cents % max_installments = 0)
);
