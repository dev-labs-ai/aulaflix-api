---
name: api-documentation
description: "Language- and framework-agnostic OpenAPI/API documentation practices: grouping and describing endpoints, documenting status codes and error responses without drifting from validation, declaring the security scheme so 'try it out' actually works, and treating the spec/docs endpoint itself as a URL that needs an access-control decision. Use whenever adding OpenAPI annotations to an endpoint, reviewing whether an API's documentation matches its actual behavior, deciding whether the spec or Swagger UI should be exposed in a given environment, or choosing between a code-first and a spec-first API workflow. Complements java-quarkus-standards (quarkus-smallrye-openapi) and java-spring-standards (springdoc), which own the annotation/extension/config specifics — this skill owns the documentation principles themselves, independent of language or framework."
---

# API Documentation

The published contract is part of the deliverable, not an afterthought generated once at the end: an endpoint nobody
can discover is an endpoint nobody can call, and a documented behavior that doesn't match the real one is worse than
no documentation, because a client trusted it.

## Code-first vs. spec-first

This skill assumes **code-first**: annotations on the actual endpoint/DTO code generate the OpenAPI spec, which is
what both Quarkus (SmallRye OpenAPI) and Spring Boot (springdoc) do out of the box, and what the rest of this skill
describes. The alternative, **spec-first** (a.k.a. "API-first" or "design-first"), writes the OpenAPI document by
hand — or gets it from a design tool — before any code exists, then generates server stubs and/or validates the
implementation against it. Some organizations mandate spec-first for governance reasons (the contract gets reviewed
and versioned independently of any one team's implementation). If that's the case here, treat that as a different
workflow this skill doesn't cover, not a variation of the rules below — don't retrofit spec-first governance onto a
code-first annotation set, or vice versa, without the team deciding to switch.

## Grouping and describing endpoints

- Group endpoints by resource (one tag per resource), with a one-line summary and, where the behavior isn't obvious
  from the method name and path alone, a longer description.
- A field-level description or example adds what cannot be inferred from the field name and type. **Never restate a
  constraint the validation annotations already declare** (`required`, a length bound, a pattern) — both the
  framework's OpenAPI integration and most tooling already derive those automatically from the validation
  annotations themselves. Writing the same fact in two places means they drift the first time only one gets updated.
- An example value should be one that would actually pass the endpoint's own validation. An example email that
  isn't a valid email, or an example date in the wrong format, teaches an integrating client the wrong shape.

## Status codes and error responses

- Document every status code that's actually part of the endpoint's contract — not just the happy path. Every
  documented error response points at the same structured error shape (the `rest-api-design` skill's error-response
  section) — a documented API whose errors are undocumented, or whose documented error shape doesn't match what the
  endpoint actually returns, is half a contract.
- Declare the status codes shared by every endpoint in a resource — or every endpoint in the whole API — **once**,
  at whatever level the framework supports (class-level, or a global customizer/bean), instead of repeating the same
  401/403/500 block on every single method. Repeated boilerplate is where documentation and reality drift fastest,
  because updating it in twelve places reliably means updating it in eleven.

## Security scheme

Declare the actual authentication scheme so the generated spec matches the API's real access-control behavior, not
an assumed one — and so "try it out" in the UI actually works instead of always returning 401. This is a
documentation bug, not just an inconvenience, when it's wrong: a spec claiming an endpoint is open when it enforces
auth (or vice versa) misleads every client integrating against it.

## The docs endpoints are real URLs

The OpenAPI spec endpoint and any UI serving it (Swagger UI or equivalent) are ordinary URLs subject to whatever
access-control chain the rest of the API uses — they don't get a free pass just because they're "just documentation."
Decide explicitly, per environment, whether each is:

- **Open in production** — legitimate when the API is public and the contract is meant to be discoverable.
- **Restricted** — behind the same auth as the API itself, or a separate permission.
- **Disabled entirely** — common for the interactive UI in production even when the raw spec stays available, or
  vice versa.

Whichever it is, it should be a decision someone made, visible in configuration — not whatever the framework
happened to default to, discovered later because someone found Swagger UI live in production or broken in staging.

## Before merging documentation for a new or changed endpoint

- [ ] Every status code the endpoint can actually return is documented, including error paths
- [ ] Every documented error points at the same error-response shape the endpoint actually returns
- [ ] No field description or example restates a validation constraint verbatim
- [ ] Example values would themselves pass the endpoint's validation
- [ ] The security scheme declared matches the endpoint's actual auth requirement
- [ ] The spec and any UI's exposure (open/restricted/disabled) is set deliberately for the target environment, not
      left at the framework default
