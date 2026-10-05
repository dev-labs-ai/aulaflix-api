-- A Lesson's video is the object the Admin linked, with its duration as read from the file: a Lesson has both or
-- neither. Both columns start empty, so every existing Lesson keeps neither.
ALTER TABLE lessons
    ADD COLUMN video_object_key VARCHAR(100),
    ADD COLUMN duration_seconds INTEGER,
    ADD CONSTRAINT ck_lessons_video_and_duration CHECK ((video_object_key IS NULL) = (duration_seconds IS NULL)),
    ADD CONSTRAINT ck_lessons_duration_positive CHECK (duration_seconds >= 1);
