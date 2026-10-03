---
name: rest-api-design
description: "Language- and framework-agnostic REST API design: URI structure and versioning, HTTP method semantics, status code selection, error-response shape, and the pagination/filtering contract for collection endpoints. Use whenever designing, reviewing, or reasoning about a REST endpoint's URI, choosing between GET/POST/PUT/PATCH/DELETE, picking a status code, designing a collection or search endpoint, versioning a public API, or deciding what a non-2xx response body should look like. Complements java-quarkus-standards and java-spring-standards, which own the framework wiring (JAX-RS/Spring MVC annotations, Panache/Spring Data pagination types, RFC 7807/9457 exception-mapper mechanics) — this skill owns the design decisions themselves, independent of language or framework."
---

# REST API Design

These are the decisions that don't change with the framework: what the URI looks like, which HTTP method does what,
which status code a given outcome gets, and what shape an error or a paginated list takes. Get these right once and
every framework-specific skill just wires them up.

## URI design

- **`/v1/{resources}`** — explicit version in the path, plural nouns (`/v1/users`, `/v1/orders`), is this skill's
  default: it's visible in logs and access lists, curl-able with no extra header, and cacheable by URL alone.
  Header-based versioning (e.g. an `Accept` media-type parameter) is an equally legitimate, common enterprise
  convention, not a lesser fallback — match whichever a codebase already uses rather than migrating it to path
  versioning for its own sake.
- **Never put a verb in a URI.** `/v1/getUser` and `/v1/createUser` are wrong; the HTTP method is the verb. An action
  that is genuinely not CRUD becomes a sub-resource noun (`POST /v1/orders/{id}/cancellation`).
- **Keep nesting shallow** (`/v1/customers/{customerId}/orders`). Two levels is a comfortable default; three is
  about the outer edge cited by community guidelines (e.g. Zalando's RESTful API Guidelines). Anything deeper is
  addressed through its own top-level URI (`/v1/orders/{orderId}`), not `/v1/customers/1/orders/2/items/3`.
- **A published version never changes shape in place.** Once clients integrate against `/v1`, an unavoidable breaking
  change ships as `/v2` beside it — removing a field, renaming one, or tightening validation on the live version
  breaks callers who had no way to know. Retiring `/v1` afterwards is a separate, announced decision.

## Method semantics

- `GET` — read. Safe and idempotent: it never changes state, and never carries a request body.
- `POST` — create. Returns `201 Created` with a `Location` header pointing at the new resource, and the created
  payload in the body.
- `PUT` — full replacement of an existing resource, or creation when the client owns the key. Idempotent: sending it
  twice leaves the same state.
- `PATCH` — partial update; only the fields present in the payload change. The request shape must distinguish an
  absent field from an explicit `null` (e.g. a wrapper/optional type per field) — this is exactly the semantics
  **RFC 7396 (JSON Merge Patch)** formalizes: `null` means clear the field, an absent key means leave it alone.
  Without that distinction, "do not touch" and "set to null" become the same request.
- `DELETE` — removal. Returns `204 No Content`, with no body.

**`POST` is the one non-idempotent method, and it's the one clients retry after a timeout.** For a create endpoint
where a duplicate side effect is expensive (charging a card, sending a payment, placing an order), accept an
`Idempotency-Key` header from the client and store the first response keyed on it — a retry with the same key
returns the original response instead of creating a second resource. The header name matches the current IETF
draft (`draft-ietf-httpapi-idempotency-key-header`) — still a draft, not a ratified RFC, but already the de facto
name production APIs use, so there's no reason to invent a different one. Skip this for endpoints where a duplicate
is harmless or naturally deduplicated (e.g. an upsert keyed on a natural unique field).

## Status codes

| Code | When |
|---|---|
| 200 OK | successful read, or an update that returns the resource |
| 201 Created | successful creation (with `Location`) |
| 204 No Content | success with nothing to return (`DELETE`) |
| 400 Bad Request | malformed payload or validation failure |
| 401 / 403 | missing identity / identity without the required role |
| 404 Not Found | the entity or the URI does not exist |
| 409 Conflict | business rule violation or unique-key conflict |
| 422 Unprocessable Entity | syntactically valid payload that fails a semantic/business-rule check (optional — see below) |

422 is optional: some APIs fold it into 400 and never introduce it, which is a legitimate, consistent choice. What's
not legitimate is a codebase that sometimes returns 400 for a business-rule failure and sometimes 422 for the same
kind of failure — pick one meaning per code, project-wide, and hold to it. Where it's used, 422 is scoped to methods
that mutate — `POST`/`PUT`/`PATCH`/`DELETE`. A `GET` with an invalid query parameter is a `400`: there's no request
body to be semantically wrong in the same sense, so don't introduce 422 for query-parameter validation.

**Check what the codebase already does before picking one.** Grep the existing exception mapper / global handler for
`422` or `UNPROCESSABLE_ENTITY`: if it's already there, match it — map the same category of failure to 422 that the
rest of the codebase does. If it's genuinely absent everywhere, default to 400-only without asking — it's the
simpler, more common convention, and it's a cheap, reversible choice either way. No need to interrupt the user over
a single status code; just be consistent with whatever this endpoint's neighbors do.

## Error response shape

Every non-2xx body is the same structured shape, everywhere in the API — never a plain string, never a shape that
differs endpoint to endpoint. **RFC 9457** ("Problem Details for HTTP APIs") is the current standard for this shape;
it obsoletes RFC 7807, which defined the identical structure under an earlier number — a codebase citing RFC 7807 in
its error handling is not wrong, just citing the older number for the same format. The shape:

```json
{
  "type": "https://example.com/problems/insufficient-balance",
  "title": "Insufficient balance",
  "status": 409,
  "detail": "Account 42 has balance 12.50 but the requested transfer is 50.00",
  "instance": "/v1/accounts/42/transfers/8f2c"
}
```

- `type` is a URI identifying the problem category — a stable value the client can switch on, even if it's never
  meant to resolve to a real page.
- `title` is a short, human-readable summary of the category, constant per `type`.
- `detail` is specific to this occurrence — safe to log, safe to show a developer, never a stack trace or a raw
  database error message.
- Additional fields (a list of field-level validation errors, a correlation ID) are allowed as extensions alongside
  the standard ones, not instead of them.

Resources/controllers never build this payload inline — a global exception handler maps domain and framework
exceptions to it in one place, through **one shared helper so every handler produces an identical envelope** — the
point of a global contract is that a client parses one shape. The framework-specific mechanics (`ExceptionMapper` in
Quarkus, `@RestControllerAdvice` in Spring) live in the corresponding standards skill.

- **Every domain exception needs its own handler.** One without a handler falls into the catch-all and reaches the
  client as a `500` — introducing an exception and introducing its handler are the same task, not two.
- **`type` stays present even with nothing to link to.** `about:blank` is the value the RFC prescribes when there's
  no documentation URI, so callers can rely on the field always existing rather than special-casing its absence.
- **Emit `timestamp` as ISO-8601, and assert the rendered shape in a test** rather than trusting the serializer's
  default — a serializer's default number/epoch format is easy to get by accident and easy to miss until a client
  parses it wrong.

## Pagination and filtering contract

This is the offset/page contract — the right default for an admin UI, an internal listing endpoint, or any
collection that isn't both large and under heavy concurrent writes. See "Offset vs. cursor pagination" below for
when to reach for something else.

- Collection endpoints accept `page` (0-indexed, default 0), `size` (default 20, clamped to a project-wide maximum
  such as 100) and `sort` (`sort=createdAt,desc`; ascending when the direction is omitted).
- **Validate the sort field against an allow-list** of sortable properties before it reaches a query. A field name
  taken from the client and passed straight into query construction is both a correctness bug (an unknown field
  surfaces as a framework-level 500, not a client-facing 400) and, if ever concatenated into raw SQL instead of
  bound through the ORM/query builder, an injection risk.
- **A paginated endpoint returns a wrapper carrying the page and the total**, never a bare list — a client cannot
  page without knowing whether more exists. Don't serialize a framework's internal paging type directly (e.g.
  Spring Data's `Page`/`PageImpl`); own the wrapper shape as part of the API contract, independent of whichever
  pagination type the persistence layer happens to return.
- Filters are additional query parameters, each documented and each validated the same way as a sort field — an
  unrecognized filter parameter is a `400`, not a silently ignored value that leaves the client believing it filtered
  when it didn't.

The framework-specific version of this contract — which type carries `Pageable`/`Sort`, how the repository layer
maps to it — lives in the corresponding standards skill.

### Offset vs. cursor pagination

Offset pagination (`page`/`size` above) has two real weaknesses at scale: a `COUNT(*)` and a deep `OFFSET` both get
more expensive as the table grows, and a row inserted or deleted while a client is paging shifts every subsequent
page — the client can see a row twice or miss one entirely. Neither matters for a bounded or admin-facing
collection; both matter for a large, high-write one.

For that case, prefer **cursor (opaque token) pagination**: the response includes a `nextCursor` (or `nextPageToken`)
encoding the last-seen sort key, and the client passes it back instead of a page number — no `OFFSET` scan, and a
cursor stays stable even as rows are inserted or deleted elsewhere in the set. This is Google's own recommendation
for its APIs (AIP-158) and what large-scale production APIs (Stripe, GitHub, Slack) use for exactly this reason. It
costs the ability to jump to an arbitrary page number and, usually, an exact total count — trade-offs worth naming
to whoever's asking for "page 47" before building it.

Don't default to cursor pagination for every collection endpoint; it's real added complexity (opaque token encoding,
no arbitrary page jump) that a small or low-write collection doesn't need. Reach for it when the growth or
concurrent-write pattern above is actually expected.
