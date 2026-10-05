package com.devlabs.aulaflix;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads the {@code webhook_events} table directly. No endpoint shows the inbox, since only Asaas talks to it, so the
 * stored row is the only place a test can observe what a delivery left behind.
 */
public final class StoredWebhookEvents {

    private final JdbcTemplate jdbc;

    public StoredWebhookEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The states of the deliveries stored under the Asaas event id: one at most. */
    public List<String> statesOf(String eventId) {
        return jdbc.queryForList("select state from webhook_events where asaas_event_id = ?", String.class, eventId);
    }

    /** The deliveries stored with exactly this body, each as its state and its event id, which may be null. */
    public List<StoredWebhookEvent> withBody(byte[] body) {
        return jdbc.query("select asaas_event_id, state from webhook_events where body = ?",
                (row, rowNumber) -> new StoredWebhookEvent(row.getString("asaas_event_id"), row.getString("state")),
                (Object) body);
    }

    /** The body stored under the Asaas event id, byte for byte. */
    public byte[] bodyOf(String eventId) {
        return jdbc.queryForObject("select body from webhook_events where asaas_event_id = ?", byte[].class, eventId);
    }

    public record StoredWebhookEvent(String eventId, String state) {
    }
}
