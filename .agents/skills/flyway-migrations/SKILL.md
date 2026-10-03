---
name: flyway-migrations
description: "Zero-downtime SQL migration practices with Flyway: backward-compatible expand/contract schema changes, locking and backfill hazards, migration naming and immutability, and the pre-merge review checklist. Use whenever writing, reviewing, or reasoning about a Flyway migration script; altering a table on a database that already holds data; adding a NOT NULL column, unique constraint, or index; renaming or dropping a column or table; backfilling a large table; or planning a schema change that must ship without downtime. Complements java-quarkus-standards and java-spring-standards, which own the framework wiring (config keys, sequence allocation) — this skill owns how to write the migration itself. Vendor-neutral SQL; works with any Flyway-fronted database."
---

# Flyway Migrations — Zero-Downtime Schema Changes

A migration runs exactly once, in production, against data you cannot regenerate. Treat every script as something
you get one shot at — review it as carefully as you would a production hotfix, because that is what it is.

## The rule that drives everything else

**Old code and new code run against the same schema at the same time.** A rolling deploy means, for some window,
instances running the previous release and instances running the new one both read and write through the migration
you just applied. A migration that only the new code can tolerate takes the old instances down until the rollout
finishes — silently, because nothing failed at deploy time, it failed at request time.

Every rule below is a consequence of that one fact.

## Naming and immutability

- Scripts live in `src/main/resources/db/migration`, named **`V<yyyyMMddHHmmss>__snake_case_description.sql`** (e.g.
  `V20260814093015__add_users_email_index.sql`). Take the timestamp from the moment you write the file — never
  renumber an existing one to "fix" ordering.
- Enable **`flyway.outOfOrder=true`** (the framework-specific keys — `quarkus.flyway.out-of-order`,
  `spring.flyway.out-of-order` — live in the java-quarkus-standards/java-spring-standards production references):
  two branches produce interleaved timestamps, so a migration merged later can carry an earlier version than one
  already applied elsewhere. Without it Flyway refuses to apply out of sequence; with it, each migration must stand
  alone or declare its dependency in SQL — never assume another branch's migration already ran.
- **Never edit a migration that has been applied anywhere** — Flyway stores a checksum per script, and changing the
  file breaks validation for every environment that already ran it, including a teammate's local database. Fix
  forward with a new script, always.
- **Flyway Community has no rollback.** Undo migrations (`U<version>__description.sql`) exist, but only on Flyway
  Teams/Enterprise — do not write one or assume one runs unless the project is confirmed to be on that edition. On
  Community, the only way back is a new forward migration that reverses the change, written and reviewed like any
  other.

## Expand / contract: the pattern for every breaking-looking change

A schema change that looks like one step is really three, spread across deploys so old code never sees a state it
can't handle.

| Step | What happens | Old code | New code |
|---|---|---|---|
| 1. Expand | Add the new shape alongside the old one | keeps working, ignores the new column | not deployed yet |
| 2. Migrate | Backfill data, deploy code that writes both / reads the new one | still working | writes both, or reads new with fallback |
| 3. Contract | Drop the old shape once nothing reads it | gone | reads/writes only the new shape |

Concrete cases:

- **Add a required column** → add it **nullable** (step 1), backfill and deploy the code that populates it on write
  (step 2), add `NOT NULL` in a *later* migration once you've verified no row is still null (step 3). Adding
  `NOT NULL` in the same migration that adds the column is the single most common way this pattern gets skipped —
  don't.
- **Rename a column** → add the new column (step 1), deploy code that writes both and reads the new one with a
  fallback to the old (step 2), backfill, then drop the old column once nothing references it (step 3). There is no
  single-statement rename that is safe under a rolling deploy, even though the database supports one.
- **Change a column's type** → same shape as a rename: add the new column with the new type, dual-write, backfill,
  cut over, drop the old one.
- **Drop a column or table** → confirm nothing in the currently-deployed code reads or writes it first (grep the
  release that's live, not just the branch you're on), then drop it in its own migration, not bundled with an
  unrelated change.
- **Add a NOT NULL foreign key** → same as a required column: add nullable, backfill, constrain later.

## Locking and backfill hazards

- **A backfill is a data migration, not a schema migration.** If touching every row could take more than a few
  seconds, it does not belong inside the same transaction as a schema change, and often does not belong in a Flyway
  migration at all — a batched background job that commits in chunks avoids holding a lock or a long transaction for
  the whole run. If it does run as a migration, batch it (e.g. update in pages of a few thousand rows) instead of one
  statement over the whole table.
- **Adding an index on a large, live table can lock writes for the duration of the build.** Use the database's
  non-locking build path when the table is write-active (e.g. Postgres `CREATE INDEX CONCURRENTLY`). Flyway detects
  `CONCURRENTLY` in the statement and automatically runs that migration outside a transaction, in every edition — no
  config needed for this specific case. (A general per-script `executeInTransaction=false` override does exist, but
  it's Flyway Teams/Enterprise only — don't assume it's available on Community.) One real gotcha either way: Flyway's
  own schema-history advisory lock can hang indefinitely if it contends with a concurrent index build, so this kind
  of migration is a bad candidate to run concurrently with anything else touching the schema history table.
- **A default value on `ADD COLUMN` can force a full table rewrite — it depends on the default, not just the
  engine version.** Postgres 11+ makes a **constant** default (`DEFAULT 0`, `DEFAULT 'active'`) instant and
  metadata-only; a **volatile** default (`DEFAULT clock_timestamp()`, `DEFAULT gen_random_uuid()`) still rewrites
  every row, on any Postgres version. Don't assume "nullable, with default" is free just because you're on a recent
  engine — check whether the specific default is constant.
- **Adding `NOT NULL` to an existing column doesn't have to mean a full-table validating scan.** On Postgres 12+,
  add a `CHECK (col IS NOT NULL) NOT VALID` constraint first (instant, no scan), validate it separately with
  `VALIDATE CONSTRAINT` (takes only a `SHARE UPDATE EXCLUSIVE` lock, so concurrent writes continue), then add the
  real `NOT NULL` — Postgres uses the validated constraint as a fast path and skips scanning the table again. Split
  these across the expand/contract steps above rather than doing a bare `ALTER COLUMN ... SET NOT NULL` once the
  backfill is done.
- **Sequences created here must match the entity's batching configuration** (`INCREMENT BY` aligned with the ORM's
  allocation size). If the target engine has no sequences, that alignment can't happen — surface it instead of
  silently changing the ID strategy.

## Before merging a migration

- [ ] Old code (the version currently deployed) still works against the schema *after* this migration applies
- [ ] A required column or constraint is added nullable/unenforced first, constrained in a later migration — never
      in the same one that introduces it
- [ ] On Postgres, a `NOT NULL` on a large/live table uses the `NOT VALID` + `VALIDATE CONSTRAINT` fast path instead
      of a bare `ALTER COLUMN ... SET NOT NULL`
- [ ] No rename or type change in a single step — it's add, dual-write/backfill, then drop, across separate
      migrations
- [ ] A backfill touching a large or hot table is batched, or moved out of the migration into a background job
- [ ] An index build on a live table uses the engine's non-locking path if the table is write-active
- [ ] Nothing in this migration assumes another branch's migration already ran, unless declared in SQL
- [ ] The file has never been applied anywhere before (new timestamp, never edited after merge)
- [ ] Ran once against a fresh database and once against a database seeded with representative data, not just an
      empty schema
