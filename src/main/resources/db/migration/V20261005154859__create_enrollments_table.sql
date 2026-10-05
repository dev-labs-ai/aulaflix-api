CREATE SEQUENCE seq_enrollment INCREMENT BY 50;

-- A Student's right to watch a Course. The row stays once the Enrollment ends, and the ending is final: access comes
-- back only through a new row. A manual one records the Admin who granted it and why; one granted by an Order will
-- name its Order instead. The Admin who ended one by hand, and why, sit beside the end.
CREATE TABLE enrollments (
    id          BIGINT       PRIMARY KEY,
    student_id  BIGINT       NOT NULL REFERENCES accounts (id),
    course_id   BIGINT       NOT NULL REFERENCES courses (id),
    started_at  TIMESTAMPTZ  NOT NULL,
    origin      VARCHAR(16)  NOT NULL,
    granted_by  BIGINT       REFERENCES accounts (id),
    grant_note  VARCHAR(500),
    ended_at    TIMESTAMPTZ,
    end_reason  VARCHAR(16),
    ended_by    BIGINT       REFERENCES accounts (id),
    end_note    VARCHAR(500),
    CONSTRAINT ck_enrollments_origin CHECK (origin IN ('ORDER', 'MANUAL')),
    CONSTRAINT ck_enrollments_manual_grant
        CHECK (origin <> 'MANUAL' OR (granted_by IS NOT NULL AND grant_note IS NOT NULL)),
    CONSTRAINT ck_enrollments_end_reason
        CHECK (end_reason IN ('REFUND', 'CHARGEBACK', 'PIX_BLOCK_UPHELD', 'MANUAL')),
    CONSTRAINT ck_enrollments_ended_with_reason CHECK ((ended_at IS NULL) = (end_reason IS NULL)),
    CONSTRAINT ck_enrollments_manual_end
        CHECK (end_reason IS DISTINCT FROM 'MANUAL' OR (ended_by IS NOT NULL AND end_note IS NOT NULL))
);

-- One active Enrollment per Student and Course; ended ones pile up beside it
CREATE UNIQUE INDEX uk_enrollments_active_student_course ON enrollments (student_id, course_id)
    WHERE ended_at IS NULL;
CREATE INDEX idx_enrollments_student_id ON enrollments (student_id);
CREATE INDEX idx_enrollments_course_id ON enrollments (course_id);
-- The Admin's list, newest first
CREATE INDEX idx_enrollments_started_at ON enrollments (started_at DESC, id DESC);
