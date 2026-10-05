-- How many launch emails the Course's launch, Coming soon to On sale, queued: one per Waitlist entry, but none to a
-- Student enrolled already. Null for a Course that has not launched from Coming soon, which notified no one. Nullable,
-- with no default, so adding it rewrites nothing.
ALTER TABLE courses ADD COLUMN notified_count INTEGER;
ALTER TABLE courses ADD CONSTRAINT ck_courses_notified_count CHECK (notified_count >= 0);

-- Unsubscribing removes an email from every Waitlist at once, which the (course_id, email) constraint's index cannot
-- serve. The table is new and holds a few rows at most, so building the index locks it only for an instant.
CREATE INDEX idx_waitlist_entries_email ON waitlist_entries (email);
