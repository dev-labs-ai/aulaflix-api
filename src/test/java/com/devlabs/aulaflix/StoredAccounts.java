package com.devlabs.aulaflix;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Reads the {@code accounts} table directly. No endpoint shows an Admin's Account, so the stored row is the only place a
 * test can observe what the admin command and the account service wrote. It also inserts Students without the HTTP
 * contract, so the admin command's tests need no BFF to show that the Admin flows refuse them.
 */
public final class StoredAccounts {

    private static final String BCRYPT_PREFIX = "{bcrypt}";

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

    /** A Student Account with a bcrypt hash made by an encoder of its own, as sign-up stores it. */
    public long insertStudent(String email, String password) {
        long id = jdbc.queryForObject("select nextval('seq_account')", Long.class);
        jdbc.update("""
                        insert into accounts (id, email, name, password_hash, role, created_at)
                        values (?, ?, 'Bia', ?, 'STUDENT', now())""",
                id, email, BCRYPT_PREFIX + new BCryptPasswordEncoder().encode(password));
        return id;
    }

    public long count() {
        return jdbc.queryForObject("select count(*) from accounts", Long.class);
    }

    public long accountSequenceValue() {
        return jdbc.queryForObject("select last_value from seq_account", Long.class);
    }

    public record StoredAccount(long id, String email, String name, String passwordHash, String role,
                                Instant createdAt) {

        /** Checks the stored hash with a bcrypt encoder of its own, not the application's. */
        public boolean hasBcryptHashOf(String password) {
            return passwordHash.startsWith(BCRYPT_PREFIX)
                    && new BCryptPasswordEncoder().matches(password, passwordHash.substring(BCRYPT_PREFIX.length()));
        }
    }
}
