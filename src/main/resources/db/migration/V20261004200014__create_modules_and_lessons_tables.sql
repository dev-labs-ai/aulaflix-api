CREATE SEQUENCE seq_module INCREMENT BY 50;
CREATE SEQUENCE seq_lesson INCREMENT BY 50;

-- A position only orders a Module among its Course's Modules, or a Lesson among its Module's Lessons: deletions leave
-- gaps, and nothing shows the stored value. Each is unique only when the transaction commits, so that reordering can
-- update one row at a time.
CREATE TABLE modules (
    id        BIGINT       PRIMARY KEY,
    course_id BIGINT       NOT NULL REFERENCES courses (id) ON DELETE CASCADE,
    position  INTEGER      NOT NULL,
    title     VARCHAR(120) NOT NULL,
    CONSTRAINT uk_modules_course_position UNIQUE (course_id, position) DEFERRABLE INITIALLY DEFERRED,
    -- The target of the Lessons' foreign key
    CONSTRAINT uk_modules_id_course UNIQUE (id, course_id)
);

-- A Lesson keeps its Course beside its Module, so that its slug is unique within the Course. The foreign key covers
-- both, so a Lesson only ever moves between Modules of its own Course.
CREATE TABLE lessons (
    id        BIGINT       PRIMARY KEY,
    course_id BIGINT       NOT NULL,
    module_id BIGINT       NOT NULL,
    position  INTEGER      NOT NULL,
    title     VARCHAR(120) NOT NULL,
    slug      VARCHAR(80)  NOT NULL,
    CONSTRAINT fk_lessons_module_course FOREIGN KEY (module_id, course_id)
        REFERENCES modules (id, course_id) ON DELETE CASCADE,
    CONSTRAINT uk_lessons_course_slug UNIQUE (course_id, slug),
    CONSTRAINT uk_lessons_module_position UNIQUE (module_id, position) DEFERRABLE INITIALLY DEFERRED
);
