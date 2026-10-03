---
name: performance
description: "Performance design for a Java 21+ JPA/Hibernate REST backend (Quarkus or Spring): detecting and fixing the N+1 query problem (JOIN FETCH, @EntityGraph, @BatchSize/default_batch_fetch_size), virtual-thread pinning and blocking-call correctness (synchronized vs. ReentrantLock, and the JDK 21 vs. JDK 24/JEP 491 distinction), connection-pool sizing against the database's own connection ceiling (HikariCP/Agroal), and index alignment for the sort/filter/pagination contract. Use whenever a lazy JPA association is being accessed, a collection is being iterated in a loop, a service is slow under load or its response time grows with data volume, a blocking call sits inside a synchronized block on a virtual-thread-per-request service, or a connection pool's size is being chosen. Complements jpa-conventions (entity/fetch-type mapping), rest-api-design (the pagination/sort contract this skill supplies the indexing side of), and java-quarkus-standards/java-spring-standards (own the framework-specific config keys) — this skill owns the performance design decisions themselves."
---

# Performance

Unlike `api-security`, this isn't anchored to one canonical external checklist — performance is
more workload-dependent, and what's "right" varies with the data volume and traffic pattern a
service actually sees. What follows is still a concrete, well-established core for this stack
(Java 21+, JPA/Hibernate, a synchronous virtual-thread-per-request REST service) rather than
generic tuning advice: the four places an ordinary CRUD service most commonly loses an order of
magnitude of throughput for no functional reason.

## The N+1 query problem

- **Detect it before guessing at a fix.** Turn on SQL statement logging (or Hibernate's
  `hibernate.generate_statistics` / a request-scoped query counter in a test) for the endpoint
  under suspicion — a single logical request that fires one query per row of a parent result set
  is the signature, not a hunch about which association "seems expensive."
- **`JOIN FETCH` or `@EntityGraph`** for a `@ManyToOne`/`@OneToOne` association read on nearly
  every request — this collapses the parent query and the association into one round trip
  instead of one extra query per row. Don't `JOIN FETCH` more than one independent `@OneToMany`/
  `@ManyToMany` collection in the same query — joining two independent to-many collections
  multiplies rows into a cartesian product, which is worse than the N+1 it was meant to fix.
- **`@BatchSize` (or `hibernate.default_batch_fetch_size`)** is the fix for a `@OneToMany`/
  `@ManyToMany` collection instead: Hibernate loads the association for a batch of parents in one
  `WHERE id IN (?, ?, ...)` query instead of one query per parent. `@BatchSize(size = 25)` turns
  101 queries (1 parent query + 100 per-row lookups) into roughly 5 — a single annotation, no
  change to the entity graph shape.

## Virtual threads: pinning and blocking-call correctness

- **On JDK 21** (this project's language baseline), a virtual thread that blocks *inside a
  `synchronized` block or method* — a blocking DB call, HTTP call, or I/O wait — stays pinned to
  its carrier platform thread for the duration. That defeats the scalability virtual threads
  exist for: the carrier can't be handed to another virtual thread while pinned, so a
  synchronous, blocking, virtual-thread-per-request model (which `java-quarkus-standards` and
  `java-spring-standards` already assume) silently degrades back toward one-platform-thread-per-
  request under exactly the code pattern that's easiest to write by accident.
- **JDK 24's JEP 491 removes most of this specific cause** — `synchronized` no longer pins in the
  common case. Don't assume it's fixed without checking: verify the project's actual running JDK
  version (`java -version`, the build's target/release) before treating pinning as a solved
  problem, and note that pinning from blocking native/JNI code is a separate cause JEP 491 does
  not address on any JDK version.
- **Prefer `java.util.concurrent.locks.ReentrantLock`** over `synchronized` in any code path that
  both guards a critical section and does I/O inside it — a `ReentrantLock` never pins, on any
  JDK version, so it removes the whole question rather than depending on which JDK the service
  happens to run on.

## Connection pool sizing

- **Bigger is not better past what the database can actually serve.** A pool sized well above the
  number of queries the database can genuinely run concurrently doesn't add throughput — it adds
  queueing and context-switching cost on the database side while requests wait for a connection
  that was never the actual bottleneck.
- Spring Boot's HikariCP default (`maximumPoolSize`, default `10`) and Quarkus's Agroal default
  (`quarkus.datasource.jdbc.max-size`) are both starting points, not sizing decisions — verify
  whichever one this project actually has configured against the database's own connection
  ceiling (e.g. Postgres' `max_connections`), shared across every instance of this service and
  every other consumer of that same database, rather than trusting either framework's
  out-of-the-box number to already fit.
- **Size for concurrent in-flight database operations, not concurrent HTTP requests.** Most
  request handling isn't blocked on the database for its entire duration, so a pool sized 1:1
  with expected concurrent requests is typically over-provisioned relative to what the database
  side of the workload needs.

## Index alignment for the sort/filter/pagination contract

- `rest-api-design`'s pagination contract validates a `sort`/filter field against an allow-list
  before it reaches a query — the performance half of that same rule is that the allowed field
  needs an actual database index. An unindexed `ORDER BY` degrades from instant to a full sort as
  the table grows, silently, with no code change to point to when it's finally noticed.
- **Deep offset pagination has a cost that grows with the offset itself**, independent of
  indexing — `OFFSET 100000` still has to walk and discard every row before it, index or not.
  `rest-api-design`'s cursor-pagination section is the design-level fix; the performance signal
  to watch for is a collection endpoint whose `page` parameter climbs into the thousands in
  actual usage — that's the concrete trigger for reaching for cursor pagination, not a default.

## Before merging

- [ ] Every `@ManyToOne`/`@OneToOne` association read on the hot path is `JOIN FETCH`'d or
      covered by an `@EntityGraph` — not left lazy and re-queried per row
- [ ] Every iterated `@OneToMany`/`@ManyToMany` collection has a `@BatchSize` (or
      `default_batch_fetch_size`) set, or a specific reason it doesn't need one
- [ ] No blocking call sits inside a `synchronized` block/method on a JDK where pinning isn't
      fixed — the project's actual JDK version was checked, not assumed
- [ ] The connection pool's max size was set deliberately against the database's own connection
      ceiling, not left at the framework default without verifying it fits
- [ ] Every allow-listed sortable/filterable field has a matching database index
- [ ] A collection endpoint expected to page deep into a large result set uses cursor
      pagination, not offset
