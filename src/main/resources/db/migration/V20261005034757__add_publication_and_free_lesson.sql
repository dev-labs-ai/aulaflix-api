-- A Lesson is published from published_at on, and never unpublished. Publishing needs its video, and since a video is
-- never unlinked, a published Lesson keeps one. The column starts empty, so every existing Lesson stays unpublished.
ALTER TABLE lessons
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_lessons_published_with_video CHECK (published_at IS NULL OR video_object_key IS NOT NULL),
    -- The target of the Free lesson's foreign key
    ADD CONSTRAINT uk_lessons_id_course UNIQUE (id, course_id);

-- The Free lesson is a Lesson of the Course itself: the foreign key covers both, as the Lessons' own does. That it is
-- published is the API's rule, since a published Lesson is never unpublished nor deleted.
ALTER TABLE courses
    ADD COLUMN free_lesson_id BIGINT,
    ADD CONSTRAINT fk_courses_free_lesson FOREIGN KEY (free_lesson_id, id) REFERENCES lessons (id, course_id);
