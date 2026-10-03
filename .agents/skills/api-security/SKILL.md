---
name: api-security
description: "Language- and framework-agnostic API security design: authentication vs. authorization, the full OWASP API Security Top 10 as it actually shows up in a CRUD REST service (BOLA/IDOR, broken function-level authorization, mass assignment, unrestricted resource consumption, sensitive business-flow abuse, SSRF, security misconfiguration, improper inventory management, unsafe consumption of third-party APIs), input validation and injection defense, rate limiting/throttling, CORS and security-response-headers, and secrets/error-message hygiene. Use whenever designing an endpoint that needs an authorization check, choosing a token/session strategy, reviewing an API against the OWASP API Top 10, configuring CORS or response headers, deciding what belongs in an error response versus a log line, or setting up rate limiting. Complements rest-api-design (URI/status/error shape, API versioning), java-quarkus-standards and java-spring-standards (own the framework wiring — SmallRye JWT/@RolesAllowed vs. Spring Security/@PreAuthorize, the actual rate-limiter dependency), jpa-conventions (parameterized queries), java-conventions (explicit mapping), and observability-logging (what must never appear in a log line) — this skill owns the security design decisions themselves, independent of language or framework."
---

# API Security

These are the security decisions that don't change with the framework: what needs an
authorization check and where, which OWASP API Top 10 items a plain CRUD REST service actually
runs into, what counts as safe input handling, and what belongs in an error response versus a
log line. The framework-specific mechanics — `@RolesAllowed` vs. `@PreAuthorize`, which JWT
library, which rate-limiter dependency — live in `java-quarkus-standards`/`java-spring-standards`.

## Authentication vs. authorization

Authentication answers *who*; authorization answers *what they're allowed to do*. Never
conflate the two — a valid, correctly-signed token proves identity, not permission. Every
endpoint that isn't genuinely public needs both checks, not just the first one: a missing
authorization check behind a passing authentication check is still a broken endpoint.

- A **stateless bearer token** (JWT via OAuth2/OIDC) fits a synchronous REST service that scales
  horizontally without sticky sessions — the same assumption `java-quarkus-standards` and
  `java-spring-standards` already make for the rest of the request-handling model. Keep access
  tokens short-lived (minutes to a few hours); use a refresh-token or re-authentication flow for
  a longer session rather than issuing a long-lived access token.
- A JWT is signed, not encrypted — treat its payload as visible to anyone holding the token.
  Never put anything in it a client shouldn't be able to read (an internal role name is usually
  fine; a secret or another user's data is not).
- **Fail closed, not open, on an unexpected exception inside an authentication or authorization
  check.** A `try/catch` around a token-validation or permission lookup that treats any error
  (a timeout calling an identity provider, a null from a lookup, a runtime exception) as "allow"
  rather than "deny" turns a transient failure into an authorization bypass — the request should
  be rejected, not let through, when the check itself couldn't complete. This is
  OWASP Top 10:2025's A10 ("Mishandling of Exceptional Conditions"), the sibling list to the
  API-specific Top 10 below; it's general-application scope, not API-specific, but it shows up
  in exactly this shape in a REST service's security filter chain.

## OWASP API Security Top 10 — how all ten show up in a CRUD REST service

Every item in the 2023 OWASP API Security Top 10 is reachable from an ordinary Java REST CRUD
service — none of the ten are web-scale-only or exotic-protocol-only concerns. This is how each
one manifests there specifically (Broken Authentication, #2, is covered above under
"Authentication vs. authorization" rather than repeated here):

- **Broken object-level authorization (BOLA/IDOR) — #1, by far the most common.** An endpoint
  like `/v1/orders/{id}` that fetches or mutates by ID without checking the caller owns or may
  access that specific resource. Using a random ID instead of a sequential one does not fix
  this — it only makes the ID harder to guess. The ownership/access check belongs in the service
  layer, at the exact point after the entity is loaded and before it's returned or mutated, on
  every endpoint that takes a resource identifier from the caller.
- **Broken object-property-level authorization / mass assignment — #3.** A request DTO or an
  entity that lets the client set a field it shouldn't (`role`, `id`, `ownerId`, `verified`).
  `java-conventions`' ban on reflection-based mapping already forces explicit, field-by-field
  mapping between DTO and entity — the discipline this item adds is making sure that explicit
  mapping itself never copies a field the client has no business setting. A request DTO's field
  list is part of the authorization boundary, not just a convenience shape.
- **Unrestricted resource consumption — #4.** No page-size cap on a collection endpoint (see
  `rest-api-design`'s pagination clamp), no rate limit on an expensive operation, no
  request-body size limit. Left unbounded, a single caller can exhaust the same resources a
  legitimate spike would.
- **Broken function-level authorization — #5.** An admin-only mutation reachable by any
  authenticated caller because the role check exists on one HTTP method of a resource (say, the
  `GET`) but was never added to a sibling method (`DELETE`, `PATCH`). Check role requirements
  per-method, not per-resource.
- **Unrestricted access to sensitive business flows — #6.** A flow that's authorized correctly
  on a per-request basis but abusable at volume or velocity — one account buying out all of a
  limited inventory, registering unlimited accounts, or retrying a discount code until one
  works — because nothing limits how often the legitimate action itself may be repeated. This is
  a different problem from #4: the individual request is cheap and fully authorized; the abuse
  is in the business outcome of repeating it. A generic per-IP rate limit doesn't fix it — the
  cap has to be business-rule-specific (per-account, per-day, per-promotion). Naming which flows
  need one is this skill's job; the concrete cap is a product decision.
- **Server-side request forgery (SSRF) — #7.** An endpoint that fetches a resource at a
  client-supplied URL (a webhook registration, an "import from URL" feature, rendering a remote
  image or PDF) without restricting the destination. Validate the *resolved* destination, not
  just the URL string, against an allow-list of expected hosts, and reject link-local/metadata
  addresses (`169.254.169.254` and equivalents) and private IP ranges before the request is
  made — checking the URL string alone misses a redirect or a DNS answer that resolves to an
  internal address only after the check has already passed.
- **Security misconfiguration — #8.** A framework's verbose error page or stack trace reaching
  the client outside a dev profile; a default or example credential left in a config file that
  ships to production; a management/actuator endpoint exposed without its own access control; a
  permissive CORS policy or missing security headers — see "CORS and security headers" below.
- **Improper inventory management — #9.** An old API version (a superseded `/v1` a service
  internally moved past to `/v2`, or a `/v0` nobody decommissioned) still deployed and reachable,
  receiving no further security fixes while exposed to the same traffic as the current version.
  `rest-api-design`'s versioning rule — "retiring `/v1` afterwards is a separate, announced
  decision" — is the design-side fix; the security angle is making sure that announced decision
  actually happens on a timeline, rather than the old version quietly staying up indefinitely.
  The same risk applies to a debug, test, or admin endpoint that shipped to production without
  being documented or access-controlled like the rest of the API.
- **Unsafe consumption of (third-party) APIs — #10.** Data received from an upstream or
  third-party API is not automatically trustworthy just because it didn't arrive from "the
  client" — validate and sanitize it the same way inbound client input is validated before
  persisting it, rendering it, or using it to build another request. Following a redirect
  blindly, trusting a `Content-Type` header, or deserializing a response with no size/depth
  limit are the same classes of bug as #7 and injection, just triggered by a response instead of
  a request.

## CORS and security response headers

- Maintain an explicit **server-side allow-list of legitimate origins** for `Access-Control-*`
  responses. Never reflect the request's `Origin` header back unchecked as if it were the
  allow-list, and never pair a wildcard `Access-Control-Allow-Origin: *` with
  `Access-Control-Allow-Credentials: true` — browsers already reject that exact combination for
  credentialed requests, which makes it a reliable signal that the CORS config was never
  actually reviewed.
- A pure JSON API (no HTML it serves itself) still benefits from a baseline set of response
  headers: `X-Content-Type-Options: nosniff` (stops a browser from MIME-sniffing a JSON response
  into something executable) and a tight `Content-Security-Policy: default-src 'none'` (there's
  no markup to render, so the strict default costs nothing). Don't blanket-copy an HTML-page
  security-header checklist here — most CSP directives about scripts/styles/images are moot for
  a service that returns only JSON.
- Add `Strict-Transport-Security` (HSTS) only once TLS is guaranteed for the life of the
  deployment, not by default. HSTS is a one-way commitment a browser remembers past the
  response that sent it — turning it on for a hostname that later needs to run without TLS
  (local/dev, certain internal deployments) locks out clients that cached the header.

## Input validation and injection defense

## Input validation and injection defense

- Validate at the boundary — bean-validation annotations on the request DTO — not only deep in
  the service layer as the sole check. A DTO that reaches the service layer already validated is
  one the rest of the code can trust.
- **SQL/JPQL injection** is the same class of bug whether the query is raw SQL or JPQL: a query
  built by concatenating a client-supplied value instead of binding it as a parameter.
  `jpa-conventions` and the framework standards skills already mandate parameter binding at the
  persistence layer — this skill just names why: a concatenated ID or search term is exactly as
  exploitable in JPQL as in raw SQL.
- **Path/command injection** — never build a file path or shell command from unsanitized user
  input. Where a feature genuinely needs to accept a path or command fragment, validate it
  against an allow-list, not a blocklist; a blocklist is a list of attacks you thought of.
- A **sort or filter field name taken from the client** (per `rest-api-design`'s pagination
  contract) is validated against an allow-list of sortable/filterable properties before it
  reaches a query — the same rule as above, applied to a value that often gets overlooked
  because it looks like metadata rather than "user input."

## Rate limiting and abuse protection

- Every authentication endpoint (login, token refresh, password reset) needs a stricter limit
  than the general API limit — brute-force and credential-stuffing attacks target exactly these
  endpoints, not the API at large.
- Prefer keying the limit on the authenticated principal when one exists, falling back to the
  client IP only for endpoints reached before authentication. An IP-only limit is trivially
  bypassed by rotating source addresses and wrongly penalizes unrelated callers sharing a NAT'd
  IP.
- The concrete mechanism — a rate-limiter dependency inside the service, or an API-gateway layer
  in front of it — is a framework/infrastructure choice outside this skill's scope. What belongs
  here is the requirement: which endpoints need a tighter limit, and what the limit is keyed on.

## Secrets and error-message hygiene

- No secret — a JWT signing key, a database credential, a third-party API key — is ever
  hard-coded or committed. It's sourced from configuration, environment, or a secret manager,
  and a missing secret at startup fails fast rather than silently falling back to a default.
- An error response never carries a stack trace, a raw framework exception message, or a raw
  database error. `rest-api-design`'s RFC 9457 shape already scopes its `detail` field to
  something "safe to log, safe to show a developer, never a stack trace or a raw database error
  message" — this is the same rule, restated from the attacker's side: a verbose error is
  reconnaissance, not just noise, telling an attacker what query ran or what framework version
  is in use.
- Anything logged is still subject to `observability-logging`'s rule on what must never appear
  in a log line. An authentication-failure log line is exactly the place secrets tend to leak by
  accident — logging the invalid password or token a caller submitted, rather than just the fact
  that the attempt failed.
