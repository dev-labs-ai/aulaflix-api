CREATE SEQUENCE seq_completed_lesson INCREMENT BY 50;

-- A Lesson a Student marked as completed: their Progress. It belongs to the Student, not to an Enrollment, so it
-- outlives one and comes back intact with the next. Only a published Lesson takes a mark, and a published Lesson is
-- never deleted, so no mark ever dangles.
CREATE TABLE completed_lessons (
    id            BIGINT       PRIMARY KEY,
    student_id    BIGINT       NOT NULL REFERENCES accounts (id),
    lesson_id     BIGINT       NOT NULL REFERENCES lessons (id),
    completed_at  TIMESTAMPTZ  NOT NULL,
    -- One mark per Student and Lesson, which a repeated mark meets and leaves as it is. Its index also serves every
    -- read, since they all start from the Student
    CONSTRAINT uk_completed_lessons_student_lesson UNIQUE (student_id, lesson_id)
);

-- A Lesson's deletion checks for marks through this index
CREATE INDEX idx_completed_lessons_lesson_id ON completed_lessons (lesson_id);
