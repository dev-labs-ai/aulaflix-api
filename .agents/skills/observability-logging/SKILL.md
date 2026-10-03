---
name: observability-logging
description: "Language- and framework-agnostic structured logging and observability practices: log level selection, what must never appear in a log line, logging an exception exactly once, structured (JSON) log output for production, and correlation/trace ID propagation across service calls via W3C Trace Context. Use whenever writing or reviewing a logging statement, deciding a log level, handling an exception that needs to be logged, configuring a service's log output format, or wiring correlation/trace IDs across HTTP calls or service boundaries. Complements java-quarkus-standards and java-spring-standards, which own the framework wiring (Logger API syntax, the quarkus-logging-json extension vs. Spring Boot's native structured-logging config, OpenTelemetry integration specifics) — this skill owns the design decisions themselves, independent of language or framework."
---

# Observability & Structured Logging

Logs exist for the person debugging an incident at 3am with no other context. Every rule below optimizes for that
reader, not for the person writing the line today.

## Log levels

- `INFO` — business-relevant milestones (entity created, status changed, job completed).
- `WARN` — a handled anomaly or an expected-but-unusual business condition (a retry succeeded, a fallback was used).
- `ERROR` — an unhandled exception or an infrastructure failure. Always pass the `Throwable` itself, not just its
  message — the message alone throws away the stack trace the next person needs.
- `DEBUG` — technical detail useful for local debugging. Off in production; never gate a decision that matters on
  whether `DEBUG` happens to be enabled somewhere.

## What never appears in a log line

Passwords, tokens, API keys, full request/response bodies, and personal identifiers (email, phone, government ID)
never go to a log — including at `DEBUG`, which gets enabled in production more often than anyone plans for. Log the
event name, the resource ID, or an entity's surrogate key instead of the personal data itself.

The stack trace goes to the log, never to the response body. What the operator sees and what the client sees are two
different concerns with two different audiences — the error-response shape (`rest-api-design` skill) governs the
latter, this skill governs the former.

## Log an exception exactly once

Log it at the boundary — a global exception handler — not at the throw site and again where it's caught, and not
re-logged at every layer it passes through on the way up. A service or repository that logs-and-rethrows produces
the same failure multiple times in the log for one real event, which is confusing during an incident, not helpful.
`ERROR` with the `Throwable` for unhandled failures; `WARN` (usually without a full stack trace) for an expected
business failure like a not-found.

## Structured (JSON) logging in production

Plain-text log lines are fine for local development. In any environment with a log aggregator (which is every
production environment worth having), emit **structured (JSON) logs** instead — one JSON object per line, with the
message, level, timestamp, logger name, and MDC context as fields. A log aggregator can filter and query fields
directly; grepping formatted text for a pattern that happens to work today breaks the next time someone tweaks the
message wording.

This is a configuration decision, not a change to how call sites log — the same `LOG.info("Created {}", id)` call
produces plain text or JSON depending only on the appender/formatter configured for the environment. The concrete
extension/config key differs by framework and lives in the corresponding standards skill.

## Correlation and trace IDs across service calls

A single user request typically produces log lines across multiple services (or at least multiple layers of one
service). Without a shared identifier stitching them together, reconstructing what happened during an incident means
guessing from timestamps.

- **W3C Trace Context** (the `traceparent` header) is the standard mechanism for propagating a trace ID across an
  HTTP call today — it's what OpenTelemetry SDKs generate and forward by default, so two services both using
  OpenTelemetry get this propagation without custom header-passing code.
- The trace ID needs to actually reach the log line, which is a separate step from generating it. The common
  mechanism is **MDC** (Mapped Diagnostic Context, an SLF4J/Logback concept adopted the same way by most JVM logging
  frameworks): a per-thread key-value map that the log pattern reads via `%X{traceId}` — if the pattern doesn't
  reference the key, populating MDC doesn't make it appear in the log regardless of how correct the propagation is.
- **This step is not automatic just because a tracing library is on the classpath, and the two mainstream JVM
  frameworks actually differ here** — verify the concrete behavior for whichever one the project uses in its
  standards skill rather than assuming either "it's automatic" or "it needs full manual wiring." Getting this wrong
  produces trace IDs that exist in telemetry but never show up in the logs someone is actually reading during an
  incident.

## Before merging a logging change

- [ ] No password, token, full body, or personal identifier appears in any log line, at any level
- [ ] An exception is logged exactly once, at the boundary, `ERROR` + `Throwable` for unhandled failures
- [ ] Production log output is structured (JSON), not plain text
- [ ] A trace/correlation ID actually appears in the rendered log line for a request — verified by triggering one
      end-to-end and reading the output, not just confirming the tracing dependency is present
