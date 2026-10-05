CREATE SEQUENCE seq_lesson_visit INCREMENT BY 50;

-- The last Lesson a Student opened in each Course, where "Continuar" resumes from. Only the last visit per Course is
-- kept: a new one overwrites the row. Like Progress, it belongs to the Student and outlives an Enrollment. Only a
-- published Lesson takes a visit, and a published Lesson is never deleted, so no visit ever dangles.
CREATE TABLE lesson_visits (
    id          BIGINT       PRIMARY KEY,
    student_id  BIGINT       NOT NULL REFERENCES accounts (id),
    course_id   BIGINT       NOT NULL REFERENCES courses (id),
    lesson_id   BIGINT       NOT NULL REFERENCES lessons (id),
    visited_at  TIMESTAMPTZ  NOT NULL,
    -- One row per Student and Course, which a new visit meets and overwrites. Its index also serves every read, since
    -- they all start from the Student
    CONSTRAINT uk_lesson_visits_student_course UNIQUE (student_id, course_id)
);

-- A Course's and a Lesson's deletions check for visits through these indexes
CREATE INDEX idx_lesson_visits_course_id ON lesson_visits (course_id);
CREATE INDEX idx_lesson_visits_lesson_id ON lesson_visits (lesson_id);
