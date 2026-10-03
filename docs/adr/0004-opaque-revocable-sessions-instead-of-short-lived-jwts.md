# Opaque, revocable sessions instead of short-lived JWTs

The API signs Accounts in itself with Spring Security and issues an opaque random token, stored only as a hash in its
own Flyway-managed table; the BFF keeps the token behind its HttpOnly cookie and forwards it as a bearer token. A
Student's session ends after 7 days without use, and after 30 days at most; an Admin's after 30 minutes without use,
and after 8 hours at most. This departs from this repo's
`api-security` standard, which favours short-lived JWTs with a refresh flow. With one issuer and one presenter, a
self-contained token saves nothing, while sign-out and password changes and resets must end sessions at once, which a
JWT can only do through a denylist. The long lifetime is a deliberate choice for Students who study every few days,
bounded by the 30-day cap and by that immediate revocation
([research](https://github.com/dev-labs-ai/aulaflix-api/issues/5),
[decision](https://github.com/dev-labs-ai/aulaflix-api/issues/7)).

## Considered Options

- **Short-lived JWT with a refresh token**: needs a signing key to manage and a denylist to revoke, for no gain with
  a single BFF.
- **Spring Session JDBC**: less code, but it stores the session id in clear and the attributes as serialized bytes.
- **Keycloak**: rejected in the research (about 2 GB of memory, link-based reset, no branch to sign-up on an unknown
  email).
