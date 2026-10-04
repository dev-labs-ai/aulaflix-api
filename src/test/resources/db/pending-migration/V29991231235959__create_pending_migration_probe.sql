-- A migration newer than any real one, which only the admin command tests put on Flyway's path.
CREATE TABLE pending_migration_probe (id BIGINT PRIMARY KEY);
