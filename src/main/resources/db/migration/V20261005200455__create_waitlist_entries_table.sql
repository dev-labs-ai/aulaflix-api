CREATE SEQUENCE seq_waitlist_entry INCREMENT BY 50;

-- Someone waiting for a Coming soon Course to go On sale, known only by their normalized email: a Visitor's, or a
-- Student's Account email, with no link to the Account, so that a Student is on a Waitlist whenever an entry holds
-- their email, however it got there. Only a Coming soon Course takes entries, and its launch deletes them, so a Draft,
-- which alone can be deleted, never has any.
CREATE TABLE waitlist_entries (
    id          BIGINT        PRIMARY KEY,
    course_id   BIGINT        NOT NULL REFERENCES courses (id),
    email       VARCHAR(254)  NOT NULL,
    joined_at   TIMESTAMPTZ   NOT NULL,
    -- One entry per Course and email, which joining again meets and leaves as it is. Its index also serves the count
    -- the Admin reads and every look-up of a Course's entries
    CONSTRAINT uk_waitlist_entries_course_email UNIQUE (course_id, email)
);
