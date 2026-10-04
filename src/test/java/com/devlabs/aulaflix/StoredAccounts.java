package com.devlabs.aulaflix;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Reads the {@code accounts} table directly. No endpoint shows an Account yet, so the stored row is the only place a
 * test can observe what the admin command and the account service wrote.
 */
public final class StoredAccounts {

    private final JdbcTemplate jdbc;

    public StoredAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredAccount> find(String email) {
        return jdbc.query("""
                        select id, email, name, password_hash, role, created_at
                        from accounts where email = ?""",
                (row, rowNumber) -> new StoredAccount(
                        row.getLong("id"),
                        row.getString("email"),
                        row.getString("name"),
                        row.getString("password_hash"),
                        row.getString("role"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()),
                email).stream().findFirst();
    }

    public long count() {
        return jdbc.queryForObject("select count(*) from accounts", Long.class);
    }

    public long accountSequenceValue() {
        return jdbc.queryForObject("select last_value from seq_account", Long.class);
    }

    public record StoredAccount(long id, String email, String name, String passwordHash, String role,
                                Instant createdAt) {

        private static final String BCRYPT_PREFIX = "{bcrypt}";

        /** Checks the stored hash with a bcrypt encoder of its own, not the application's. */
        public boolean hasBcryptHashOf(String password) {
            return passwordHash.startsWith(BCRYPT_PREFIX)
                    && new BCryptPasswordEncoder().matches(password, passwordHash.substring(BCRYPT_PREFIX.length()));
        }
    }
}
