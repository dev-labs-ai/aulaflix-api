package com.devlabs.aulaflix;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads the {@code outbox_emails} table directly. No endpoint shows the outbox, so the stored row is the only place a
 * test can observe the attempts an email took and when it is due again.
 */
public final class StoredOutboxEmails {

    private final JdbcTemplate jdbc;

    public StoredOutboxEmails(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The only email queued for the address, failing the test unless there is exactly one. */
    public StoredOutboxEmail onlyOneTo(String recipient) {
        return jdbc.queryForObject("""
                        select template, state, attempts, next_attempt_at, sent_at
                        from outbox_emails where recipient = ?""",
                (row, rowNumber) -> new StoredOutboxEmail(
                        row.getString("template"),
                        row.getString("state"),
                        row.getInt("attempts"),
                        row.getObject("next_attempt_at", OffsetDateTime.class).toInstant(),
                        row.getObject("sent_at", OffsetDateTime.class) == null ? null
                                : row.getObject("sent_at", OffsetDateTime.class).toInstant()),
                recipient);
    }

    /** The recipients of every email queued from the template whose body holds the text, one per email. */
    public List<String> recipientsOf(String template, String bodyText) {
        return jdbc.queryForList("""
                select recipient from outbox_emails
                where template = ? and strpos(body, ?) > 0 order by recipient""", String.class, template, bodyText);
    }

    /**
     * Deletes every email still pending, whoever queued it, so that a test that fails sends or counts them starts
     * from an empty queue. Other tests queue under clocks of their own, so their emails may fall due at any time.
     */
    public void discardPending() {
        jdbc.update("delete from outbox_emails where state = 'PENDING'");
    }

    /**
     * Deletes every email still pending but those to the addresses given, so that a drain sends only the test's own:
     * an email to every Admin goes to each Admin the whole suite has made, hundreds of them, which no test reads.
     */
    public void discardPendingExceptTo(String... recipients) {
        jdbc.update("delete from outbox_emails where state = 'PENDING' and recipient <> all (?)",
                (Object) recipients);
    }

    public record StoredOutboxEmail(String template, String state, int attempts, Instant nextAttemptAt,
                                    Instant sentAt) {
    }
}
