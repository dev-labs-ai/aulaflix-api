# Authentication options for a Spring Boot API behind a Nuxt BFF

- **Ticket**: [#5][ticket], part of the map [#1][map]
- **Researched**: 2026-10-03
- **Question**: What are the viable ways to authenticate users of this API, given that the Nuxt/Nitro server is a BFF
  that holds the browser session and the browser never calls the API? Compare Spring Security in the API, Keycloak
  and other self-hostable options. Cover how the BFF-to-API call carries the Student's identity, and the
  account-enumeration risk of the web's email-first step. Recommend one.

Every factual claim below links to its source. Text marked **Analysis** is reasoning from those facts, not a sourced
claim. Text marked **Unverified** could not be confirmed against a primary source.

## Answer

Build authentication into the API with **Spring Security 7.1.1**, the version managed by the Spring Boot 4.1.1
parent. Use an **opaque, server-side session token** for the BFF-to-API hop rather than a self-issued JWT:

- On sign-in, the API returns a random token.
- The BFF keeps the token behind its own HttpOnly cookie and sends it as `Authorization: Bearer` on every API call.
- The API stores only a hash of the token, so sign-out, a password change or an Admin action can revoke it at once.

Hash passwords through Spring Security's `DelegatingPasswordEncoder`. Its default is bcrypt at cost 10. Argon2id is
OWASP's first choice, but it needs an extra library and explicit parameters. Rate limiting, lockout, the 6-digit
reset code and the email-confirmation token are application code whichever option is chosen.

Keycloak 26.8.0 can do the job, but the cost is high for this MVP:

- Its recommended memory limit for a small production deployment is 2 GB.
- It sends password resets as links, not codes.
- Its identity-first form does not branch to sign-up.
- Keeping the web's own forms would need the password grant, which RFC 9700 says MUST NOT be used.

The trade-off: the team owns and must test the security-critical account code, in exchange for no extra container,
no extra datastore and full control of the email-first UX.

## Versions pinned from the project's artifacts

| Component                   | Version                       | Evidence                                                                                                                                                                                             |
|-----------------------------|-------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Spring Boot parent          | 4.1.1                         | `pom.xml` (`spring-boot-starter-parent` 4.1.1)                                                                                                                                                       |
| Spring Security             | **7.1.1**                     | `<spring-security.version>7.1.1</spring-security.version>` in the [Boot 4.1.1 BOM][boot-bom] (line 205 of the copy in `~/.m2`)                                                                        |
| Spring Framework            | 7.0.9                         | `<spring-framework.version>` in the [Boot 4.1.1 BOM][boot-bom]                                                                                                                                       |
| Spring Session              | 4.1.1                         | `<spring-session.version>` in the [Boot 4.1.1 BOM][boot-bom]                                                                                                                                         |
| Spring Authorization Server | ships inside Spring Security  | `spring-security-oauth2-authorization-server` is listed in [`spring-security-bom` 7.1.1][ss-bom]; "Spring Authorization Server is now part of Spring Security" ([What's new in 7.0][ss-new-70])      |
| Keycloak                    | 26.8.0, released 2026-10-01   | [Keycloak 26.8.0 release notes][kc-2680], [GitHub release][kc-gh-2680]                                                                                                                                 |
| Ory Kratos (OSS)            | v26.2.0, released 2026-03-20  | [GitHub releases][kratos-gh]; Apache-2.0 license (GitHub repository metadata)                                                                                                                       |
| Zitadel                     | v4.19.4, released 2026-10-01  | [GitHub releases][zitadel-gh]; AGPL-3.0 license (GitHub repository metadata)                                                                                                                         |

The Boot 4.1.1 BOM manages these starters: `spring-boot-starter-security`,
`spring-boot-starter-security-oauth2-resource-server`, `spring-boot-starter-security-oauth2-authorization-server` and
`spring-boot-starter-session-jdbc` ([Boot 4.1.1 BOM][boot-bom]). The Spring Security, Spring Boot and Spring Session
reference pages cited below all displayed version 7.1.1, 4.1.1 and 4.1.1 respectively when fetched. The
`/reference/7.1/` and `/4.1/` URLs currently redirect to those pages.

### Spring Security 7 changes that matter here

Code samples written for Spring Security 6.x will not compile as-is. According to [What's new in 7.0][ss-new-70]:

- `and()` was removed from the `HttpSecurity` DSL in favour of the lambda methods.
- `authorizeRequests` was removed in favour of `authorizeHttpRequests`.
- `MvcRequestMatcher` and `AntPathRequestMatcher` were removed in favour of `PathPatternRequestMatcher`.
- The Access API (`AccessDecisionManager`, `AccessDecisionVoter`) moved to a new `spring-security-access` module.

Spring Security 7.0 also added the following ([What's new in 7.0][ss-new-70]):

- Multi-factor authentication support.
- Password4j-based password encoders.
- A builder for `NimbusJwtEncoder` that accepts an RSA or EC key pair, or a secret key.
- The authorization server, with PKCE enabled by default.

Spring Security 7.1 adds more MFA conditions, `RestClientOpaqueTokenIntrospector` and a `PreFlightRequestFilter`
([What's new in 7.1][ss-new-71]).

Once `spring-boot-starter-security` is added, Spring Boot 4.1.1 does the following by default
([Boot: Spring Security][boot-sec]):

- Creates an in-memory `UserDetailsService` with a single user named `user` and a random password logged at WARN.
- Protects the whole application with form login or HTTP Basic, depending on the `Accept` header.

**Analysis**: the API needs its own `SecurityFilterChain` from the first commit that adds the starter.

## Context: what the web does today

These are leads from the prototype, not a contract ([map #1][map]):

- `/entrar` starts with the email. `POST /api/auth/look-up-email` returns `{ exists }`, and the page then asks for the
  password or offers to create the account ([look-up-email.post.ts][web-lookup], [auth.ts copy][web-auth-copy]).
- Sign-up signs the user in straight away. Email confirmation comes later, through a link
  ([sign-up.post.ts][web-signup], [auth.ts copy][web-auth-copy]).
- Sign-up answers "an account with this email already exists" when the email is taken ([auth.ts copy][web-auth-copy]).
- Password reset uses a 6-digit emailed code with a 60-second resend timer. The reset form's button reads "Salvar e
  entrar" (save and sign in). The minimum password length is 8 characters ([auth.ts copy][web-auth-copy]).
- The prototype session cookie is `httpOnly`, `sameSite: 'lax'`, `secure` in production, and lasts 7 days
  ([server/utils/auth.ts][web-auth-utils]).

## Option (a): built into the API with Spring Security 7.1.1

### What the framework provides and what stays application code

| Need                                     | Spring Security 7.1.1                                                                                                                                                                                                                                                                                                                                         |
|------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Password hashing                         | `PasswordEncoderFactories.createDelegatingPasswordEncoder()` and the bcrypt, Argon2, scrypt and PBKDF2 encoders ([Password Storage][ss-pw]). See [Password hashing defaults](#password-hashing-defaults).                                                                                                                                                       |
| Checking a password at sign-in           | `DaoAuthenticationProvider`. In the 7.1.1 jar it has `prepareTimingAttackProtection` and `mitigateAgainstTimingAttack` methods, and `hideUserNotFoundExceptions` defaults to `true` (inspected with `javap` on [`spring-security-core-7.1.1.jar`][ss-core-jar]).                                                                                                  |
| Rejecting breached passwords             | `CompromisedPasswordChecker`, implemented by `HaveIBeenPwnedRestApiPasswordChecker` and picked up automatically by `DaoAuthenticationProvider` when declared as a bean ([Password Storage][ss-pw])                                                                                                                                                              |
| Reading a token from the request         | Bearer token resolution from the `Authorization` header, configurable to another header ([Bearer Tokens][ss-bearer])                                                                                                                                                                                                                                           |
| Validating the token                     | A JWT resource server ([JWT][ss-jwt]) or an opaque-token resource server with a pluggable `OpaqueTokenIntrospector` bean ([Opaque Token][ss-opaque])                                                                                                                                                                                                             |
| Server-side sessions in a header         | Spring Session 4.1.1 `HeaderHttpSessionIdResolver.xAuthToken()`, backed by JDBC, Redis or Hazelcast ([Spring Session: HttpSession][spring-session])                                                                                                                                                                                                          |
| Roles                                    | Authorities on the `Authentication`. JWT and opaque-token authorities can be remapped, for example to a `ROLE_` prefix ([JWT][ss-jwt], [Opaque Token][ss-opaque])                                                                                                                                                                                               |
| Emailed one-time tokens                  | One-Time Token **login**, delivered as a magic link. The default store is in-memory, with `JdbcOneTimeTokenService` for production and a 5-minute default expiry. The docs present it only as a login mechanism and do not show a numeric-code format ([One-Time Token Login][ss-ott]).                                                                          |
| MFA                                      | `@EnableMultiFactorAuthentication`, with password, one-time-token, WebAuthn and X.509 factors ([MFA][ss-mfa])                                                                                                                                                                                                                                                   |
| Lockout                                  | Only the hooks. `UserDetails.isAccountNonLocked()` and `LockedException` exist, and `AuthenticationFailureBadCredentialsEvent` is published (`javap` on [`spring-security-core-7.1.1.jar`][ss-core-jar]; [Boot: Spring Security][boot-sec] for the default event publisher). Counting failures and deciding when to lock is application code.             |
| Rate limiting                            | None. The 7.1.1 core and web jars contain no rate-limiting classes, and the Boot 4.1.1 BOM manages no rate-limiter library such as Bucket4j or Resilience4j (searched in [`spring-security-core-7.1.1.jar`][ss-core-jar], `spring-security-web-7.1.1.jar` and the [Boot 4.1.1 BOM][boot-bom]).                                                                       |
| Sign-up, email confirmation, reset code, password change | Not provided. These are controllers and services the API writes itself. **Analysis**: OTT could carry the confirmation link, but it signs the user in, which is not what confirmation should do.                                                                                                                                         |

### Session token vs JWT for the BFF-to-API hop

| Aspect                     | Opaque server-side token                                                                                                                                                                                                                       | Self-issued JWT carried by the BFF                                                                                                                                                                                                                       |
|----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Revocation                 | Delete the row. OWASP requires server-side invalidation on logout and expiry ([Session Management CS][owasp-session]).                                                                                                                          | "Signed JWTs lack built-in revocation". The options are short lifetimes, a `jti` denylist or a token status list ([JWT CS][owasp-jwt]).                                                                                                                  |
| Per-request cost           | One indexed lookup. **Analysis**: negligible at MVP scale.                                                                                                                                                                                      | Signature and claims check only. The resource server checks `exp`, `nbf` and `iss`, with a 60-second default clock skew ([JWT][ss-jwt]).                                                                                                                 |
| Keys and secrets           | None. Token entropy must be at least 64 bits ([Session Management CS][owasp-session]).                                                                                                                                                          | A signing key to generate, store and rotate. `NimbusJwtEncoder` and `NimbusJwtDecoder.withSecretKey(...)` support it ([What's new in 7.0][ss-new-70], [JWT][ss-jwt]).                                                                                    |
| Spring Security 7.1.1 fit  | A custom `OpaqueTokenIntrospector` bean on the opaque-token resource server ([Opaque Token][ss-opaque]), or Spring Session with a header resolver ([Spring Session][spring-session]).                                                            | First-class JWT resource server ([JWT][ss-jwt]).                                                                                                                                                                                                         |
| What the BFF holds         | A meaningless random string.                                                                                                                                                                                                                    | A readable token that grants access until it expires.                                                                                                                                                                                                    |

**Analysis**: a JWT pays off when many parties must verify a token without calling its issuer. Here there is one
issuer, the API, and one presenter, the BFF. The account flows also need immediate revocation:

- sign-out;
- password change and password reset, where OWASP asks to invalidate existing sessions
  ([Forgot Password CS][owasp-forgot]);
- an Admin cutting off access.

A JWT would need a denylist for these cases, which brings back server state without removing the key management. The
opaque token is the simpler and safer choice for this hop.

There are two ways to implement the opaque token in 7.1.1:

1. **A table of your own.** Store a random token of at least 256 bits (**Analysis**: well above OWASP's 64-bit floor),
   hashed at rest, in a Flyway-managed table with the user id, the expiry and the last use. Resolve it with a custom
   `OpaqueTokenIntrospector` bean ([Opaque Token][ss-opaque]) or a small filter. **Analysis**: hashing the token means a
   leaked table row cannot be replayed, and listing or deleting all of a user's sessions is a plain query.
2. **Spring Session JDBC** with `HeaderHttpSessionIdResolver.xAuthToken()` ([Spring Session][spring-session]). Its
   PostgreSQL schema stores `SESSION_ID CHAR(36)` in clear and the attributes as serialized `BYTEA`
   ([schema-postgresql.sql at 4.1.1][spring-session-schema]). Spring Security's session-fixation protection and
   concurrent-session control work on the `HttpSession` ([Session Management][ss-session]). **Unverified**: whether
   both behave the same when the session id travels in a header rather than a cookie.

**Analysis**: option 1 fits the repo's Flyway-first standards better and keeps the token hashed at rest. Option 2 is
less code. The choice can be left to the spec.

## Option (b): Keycloak self-hosted in Docker

### Resource needs (26.8.0 docs)

- "For smaller production-ready deployments, the recommended memory limit is 2 GB." To approach the old 512 MB heap,
  "set the memory limit to at least 750 MB". The heap defaults to 70% of container memory, and production must run
  `start --optimized` with an explicit database, not `start-dev` ([Keycloak: containers][kc-containers]).
- The sizing guide puts base memory at 1250 MB with realm caches and 10,000 cached sessions, plus about 300 MB of
  non-heap memory. It allocates 1 vCPU per 15 password logins per second, using the default Argon2 hashing
  ([Keycloak: memory and CPU sizing][kc-sizing]).
- 26.8.0 advertises "reduced memory usage" and better Argon2 memory management, but the release notes give no figures
  ([Keycloak 26.8.0 release][kc-2680]).
- **Analysis**: Keycloak needs its own database, which can be a second database on the same PostgreSQL server. It
  also needs a public hostname for its login pages.
- **Unverified**: the owner's VPS size, so whether 2 GB extra fits is not known.

### pt-BR email theming

- Email themes are FreeMarker templates plus message bundles. Each email has a subject, a plain-text body and an HTML
  body. Themes deploy as an archive in `providers/` ([Keycloak: themes][kc-themes]).
- A realm can override message keys, but Keycloak calls this "not the recommended way" to localize. Missing
  translations fall back to English ([Keycloak: localization][kc-l10n]).
- `messages_pt_BR.properties` exists for both the login and the email theme at tag 26.8.0, under
  `resources-community` ([login pt_BR][kc-ptbr-login], [email pt_BR][kc-ptbr-email]). **Analysis**: the folder name
  suggests community-maintained translations. The localization guide does not say whether they are officially
  maintained ([Keycloak: localization][kc-l10n]).

### Fit with the web's flows

- **Email-first sign-in.** Keycloak has an identity-first `UsernameForm` authenticator. When the email is unknown, it
  answers with the same "invalid username or email" challenge, after a dummy hash. It does not branch to registration
  ([UsernameForm.java][kc-usernameform], [AbstractUsernameFormAuthenticator.java][kc-abstractusername]). Reproducing
  the web's "no account yet, create one" step needs a custom authenticator or theme. **Analysis**: that means Java
  SPI code in a second codebase.
- **Keeping the Nuxt forms.** This would need the Resource Owner Password Credentials grant. RFC 9700 says it "MUST
  NOT be used" ([RFC 9700 §2.4][rfc9700]). Keycloak's own docs repeat this and add that with this grant, self-service
  flows such as registration and required actions are not supported ([Keycloak: OIDC layers][kc-oidc-layers]). The
  realistic Keycloak setup therefore replaces `/entrar` and `/redefinir-senha` with Keycloak-hosted pages.
- **Password reset by 6-digit code.** Not built in. The forgot-password flow emails a link
  ([forgot-password.adoc at 26.8.0][kc-forgot]). The reset-credentials authenticators at 26.8.0 are
  `ResetCredentialChooseUser`, `ResetCredentialEmail`, `ResetOTP` and `ResetPassword`
  ([resetcred package][kc-resetcred]). A code would need a custom authenticator.
- **Brute-force detection.** Present but "disabled by default". A locked user sees the same "Invalid username or
  password" message ([brute-force.adoc at 26.8.0][kc-brute]). Since 26.8.0, lockout state persists in the database by
  default ([Keycloak 26.8.0 release][kc-2680]).

## Option (c): other self-hostable options

| Option                                                  | Facts                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Fit                                                                                                                                                                                       |
|---------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Spring Authorization Server (now in Spring Security 7)  | Supports the authorization code, client credentials, refresh token, device code and token exchange grants, plus OIDC. The password grant is not supported ([Authorization Server][ss-as]).                                                                                                                                                                                                                                                                         | **Analysis**: the user would still log in on a page served by the authorization server, through browser redirects. That is the Keycloak UX problem without Keycloak's features. Overkill for one first-party BFF. |
| Ory Kratos v26.2.0 (Apache-2.0)                         | Headless API flows built for custom UIs ([Kratos: custom UI][kratos-ui]). Recovery uses a one-time **code** by default; magic links are legacy. Codes last 1 hour by default. `notify_unknown_recipients` emails unknown addresses to prevent enumeration ([Kratos: recovery][kratos-recovery]). When self-hosting, rate limiting "is the responsibility of the administrator" ([Kratos: security][kratos-security]). | Closest to the web's custom forms and code-based reset. Still a second service and a second identity store. **Unverified**: Kratos resource footprint, and how the API would validate Kratos sessions per request. |
| Zitadel v4.19.4 (AGPL-3.0)                              | A Session API for building a custom login UI with username and password ([Zitadel: custom login UI][zitadel-ui]).                                                                                                                                                                                                                                                                                                                                                  | Same "second service" cost. The AGPL-3.0 license needs a legal check. Not evaluated further.                                                                                              |

## How the BFF carries the Student's identity

| Pattern                                                                           | Assessment                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|-----------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **1. User token as a bearer token (recommended)**                                 | The API issues the opaque token at sign-in. The BFF stores it behind its own HttpOnly cookie and forwards it as `Authorization: Bearer` ([Bearer Tokens][ss-bearer]). This matches RFC 10017's BFF pattern, where the BFF keeps tokens in a cookie-based session, adds them to requests it forwards to the resource server, and avoids "direct exposure of any tokens" to the browser ([RFC 10017][rfc10017]). RFC 10017 cookie rules: Secure and HttpOnly are MUST; a name prefix showing the cookie was set over HTTP, for example `__Host-Http-`, and `SameSite=Strict` are SHOULD ([RFC 10017][rfc10017]). |
| 2. API-issued JWT carried by the BFF                                              | Works with the JWT resource server ([JWT][ss-jwt]), but brings the revocation problem described above ([JWT CS][owasp-jwt]).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| 3. BFF asserts the user in a header (`X-User-Id`) and the API trusts the BFF      | This is Spring's pre-authentication model (`RequestHeaderAuthenticationFilter`). Spring warns that "the framework performs no authentication checks at all" and that if an attacker can forge the headers, "they could potentially choose any username they wished" ([Pre-Authentication][ss-preauth]). **Analysis**: one bug or SSRF in the BFF would impersonate every user, and passwords would still have to be checked somewhere. Not recommended.                                                       |

**Analysis**: the browser cookie can either hold the API token itself, sealed or plain, or a key into a Nitro-side
session store. Both satisfy RFC 10017's cookie rules. The choice belongs to the web's change plan.

## Password hashing defaults

- **Spring Security 7.1.1 default.** `PasswordEncoderFactories.createDelegatingPasswordEncoder()` encodes with bcrypt
  under the `{bcrypt}` prefix. `BCryptPasswordEncoder` defaults to strength 10. Spring advises tuning the strength so
  a verification takes "roughly 1 second" ([Password Storage][ss-pw]). A local run of the 7.1.1 crypto jar produced
  `{bcrypt}$2a$10$…` ([`spring-security-crypto-7.1.1.jar`][ss-crypto-jar]).
- **Changing algorithm later.** `DelegatingPasswordEncoder` keeps verifying old `{id}` hashes while encoding new ones
  with the current default ([Password Storage][ss-pw]). OWASP recommends allowing "a mix of old and new hashing
  algorithms" during a transition ([Password Storage CS][owasp-pwstore]).
- **bcrypt's 72-byte limit.** Spring's 7.1.1 `BCrypt` class throws "password cannot be more than 72 bytes" (string
  constant in [`spring-security-crypto-7.1.1.jar`][ss-crypto-jar]). OWASP says to enforce a 72-byte maximum with
  bcrypt ([Password Storage CS][owasp-pwstore]) and to allow a maximum of at least 64 characters
  ([Authentication CS][owasp-authn]). **Analysis**: 64 multi-byte characters can exceed 72 bytes, so the contract
  needs an explicit byte-based limit if bcrypt is kept.
- **OWASP's order of preference.**
  1. Argon2id with at least 19 MiB of memory, 2 iterations and parallelism 1.
  2. scrypt.
  3. bcrypt, "for legacy systems", with a work factor of 10 or more.
  4. PBKDF2 for FIPS.

  ([Password Storage CS][owasp-pwstore])
- **Argon2 in Spring.** `Argon2PasswordEncoder` "requires BouncyCastle" ([Password Storage][ss-pw]).
  `defaultsForSpringSecurity_v5_8()` uses a 16-byte salt, a 32-byte hash, parallelism 1, 16,384 KiB of memory and
  2 iterations ([Argon2PasswordEncoder Javadoc][ss-argon2-api]). That memory is **below** OWASP's 19 MiB minimum, so
  explicit parameters would be needed.
- **Argon2 via Password4j.** Spring Security 7.0 added Password4j-based encoders such as
  `Argon2Password4jPasswordEncoder` ([Password Storage][ss-pw]).
- **Neither library is managed.** Neither BouncyCastle nor Password4j appears in the Boot 4.1.1 BOM or in
  `spring-security-bom` 7.1.1 (searched in [Boot 4.1.1 BOM][boot-bom] and [`spring-security-bom` 7.1.1][ss-bom]).
  Either would be a new dependency with a version pinned by the project.
- **Unverified**: the default Argon2 parameters of `Argon2Password4jPasswordEncoder`.
- **Keycloak's default**, for comparison: Argon2 with 5 iterations and a minimum memory size of 7 MiB
  ([Keycloak: sizing][kc-sizing]).

**Analysis**: choose between two paths.

- **bcrypt.** The framework default, with no new dependency. It needs a 72-byte password cap and a measured cost of
  at least 10.
- **Argon2id.** OWASP's first choice. It needs a new dependency and explicit parameters of at least 19 MiB, t=2, p=1.

Either way, go through `DelegatingPasswordEncoder` so the choice can change later. Add a `CompromisedPasswordChecker`
on sign-up, reset and change ([Password Storage][ss-pw]). OWASP points to checking breached-password datasets
([Credential Stuffing CS][owasp-stuffing]). **Analysis**: the HIBP checker calls an external API from the VPS. A
self-hosted dataset is the offline alternative ([Credential Stuffing CS][owasp-stuffing]).

## Account enumeration with an email-first sign-in

What the standards say:

- OWASP wants identical responses for login, password recovery and account creation. Its account-creation example
  is "A link to activate your account has been emailed to the address provided." It also wants uniform response
  times ([Authentication CS][owasp-authn]).
- OWASP admits the cost: generic messages can confuse legitimate users, the call depends "on the criticality of the
  application", and "CAPTCHA can be applied to a feature for which a generic error message cannot be returned because
  the user experience must be preserved" ([Authentication CS][owasp-authn]).
- Multi-step logins "should be mindful that they do not facilitate user enumeration"
  ([Credential Stuffing CS][owasp-stuffing]).
- Forgot-password must "return a consistent message for both existent and non-existent accounts" in consistent time,
  with per-account rate limiting or a CAPTCHA ([Forgot Password CS][owasp-forgot]).

**Analysis of the web's flow**:

1. `look-up-email` is a pure existence oracle. It needs only an email address, no name and no password, so it is
   the cheapest enumeration endpoint the API would expose.
2. The web's sign-up also reveals existence ("an account with this email already exists"). Because sign-up signs the
   user in immediately, it cannot return OWASP's generic "we emailed you" response without changing the UX.
   Removing only the look-up step would therefore not remove enumeration.
3. Password reset can and should stay non-enumerating. Answer "if an account exists, we sent a code", spend equal
   time on both paths, and optionally email unknown addresses, as Kratos's `notify_unknown_recipients` does
   ([Kratos: recovery][kratos-recovery]).

There are two ways forward:

- **(i) Keep email-first and accept enumeration as a documented risk.** Throttle `look-up-email`, sign-up and sign-in
  per client IP and globally. Add a CAPTCHA after a threshold, as OWASP suggests when UX must be preserved
  ([Authentication CS][owasp-authn]). Monitor volume.
- **(ii) Make the flow non-enumerating.** This changes the web's UX. Always show a password step, and handle sign-up
  through an emailed link before the session starts.

**Analysis**: for a course storefront, (i) is a defensible MVP choice. Record it as an ADR, since it is
hard to reverse once users are used to it. Spring Security's `DaoAuthenticationProvider` already evens out sign-in
timing for unknown users ([`spring-security-core-7.1.1.jar`][ss-core-jar]). The look-up and reset endpoints are
custom code and must do the same themselves.

## Brute-force and credential-stuffing mitigations

From OWASP, with what each means here:

- **MFA** is "by far the best defense" against password attacks ([Authentication CS][owasp-authn],
  [Credential Stuffing CS][owasp-stuffing]). **Analysis**: not in the MVP flows for Students. Spring Security's MFA,
  with an emailed one-time token as the second factor, is an option to consider for Admin accounts ([MFA][ss-mfa]).
- **Account lockout** has a threshold, an observation window and a duration. It must not become a denial of service
  against other users, and forgot-password should keep working while an account is locked
  ([Authentication CS][owasp-authn]). For reference, Keycloak's permanent-lockout defaults are 30 failures and a
  1-second quick-login check ([Keycloak brute force][kc-brute]).
- **CAPTCHA** is defence in depth, possibly only after failures ([Authentication CS][owasp-authn]).
- **IP throttling** helps but should not be the only defence. Combine it with device and connection fingerprinting,
  breached-password checks and notifications of unusual events ([Credential Stuffing CS][owasp-stuffing]).
- **Reset codes.** OWASP describes PINs of 6 to 12 digits. Codes must come from a CSPRNG, be "long enough to protect
  against brute-force attacks", be linked to one user, be single use and be stored securely. A PIN should open "a
  limited session" that only permits the reset ([Forgot Password CS][owasp-forgot]). **Analysis**: a 6-digit code has
  only 10^6 values. It is safe only with a small attempt cap per code, a short expiry and per-account throttling. It
  must be stored as an HMAC or slow hash, never a plain SHA-256, because a plain hash of 10^6 values is reversible
  offline.
- **Re-authentication.** A password change requires the current password ([Authentication CS][owasp-authn]). After a
  reset, notify the user by email and invalidate sessions. "Don't automatically log the user in"
  ([Forgot Password CS][owasp-forgot]).
- **Where to enforce it.** Neither Spring Security 7.1.1 nor the Boot 4.1.1 BOM provides rate limiting (see the
  table in option (a)). Self-hosted Kratos leaves it to the administrator too ([Kratos: security][kratos-security]).
  It lands in the reverse proxy, in a library added to the API, or in both.
- **The BFF hides the client IP.** Every API request comes from the BFF, so per-IP limits at the API need the
  browser's IP forwarded by the BFF. The API must trust that header only from the BFF. Spring Boot's
  `server.forward-headers-strategy` defaults to `NONE` outside cloud platforms, and Tomcat's `RemoteIpValve` trusts
  only the configured `internal-proxies` ([Boot: behind a proxy][boot-proxy]).

## Recommendation

**Option (a).** Build authentication into the API with Spring Security 7.1.1:

- an opaque, hashed, server-side session token, carried by the BFF as a bearer token;
- passwords through `DelegatingPasswordEncoder`, with bcrypt plus a 72-byte cap or Argon2id with explicit parameters,
  and a `CompromisedPasswordChecker`;
- `ROLE_STUDENT` and `ROLE_ADMIN` authorities;
- the account flows (sign-up, confirmation link, 6-digit reset code, password change) as API endpoints following the
  OWASP rules above.

**Trade-off accepted**:

- The project owns security-critical code: tokens, codes, lockout, throttling and timing equalization. That code
  needs thorough tests and a `security-auditor` pass before release.
- In return there is no Keycloak container (2 GB memory recommended), no second datastore, no hosted login pages and
  no custom SPI for the email-first branch or the reset code.
- The web keeps its own UX.

**When to revisit**: Google and GitHub sign-in are deferred past the MVP ([map #1][map]). Social login would itself
need an `oauth2Login` client and account linking in the API. If more apps or SSO appear, Keycloak or Kratos become
worth their footprint.

## Findings that affect other parts of the plan

1. **Abuse protection** (map, "Not yet specified"): Spring Security and the Boot BOM provide no rate limiter, so the
   abuse-protection work must choose a mechanism. Per-IP limits only work if the BFF forwards the client IP and the
   API trusts it only from the BFF ([Boot: behind a proxy][boot-proxy]).
2. **Container topology**: no identity-provider container is needed under the recommendation. If the API must be
   reachable from the internet, for example for payment-gateway webhooks, the reverse proxy should expose only those
   paths, or the BFF should present a service credential. **Analysis**: otherwise `look-up-email` and sign-in are
   public API endpoints that bypass the BFF.
3. **Web change plan and BFF mapping**: four places where the web departs from OWASP need a decision.
   - The minimum password length is 8. OWASP calls passwords shorter than 15 characters weak without MFA, and shorter
     than 8 weak with MFA ([Authentication CS][owasp-authn]).
   - "Salvar e entrar" signs the user in after a reset, against "Don't automatically log the user in"
     ([Forgot Password CS][owasp-forgot]).
   - The 7-day session sits against OWASP's idle timeouts of 15–30 minutes for low-risk apps and absolute timeouts of
     4–8 hours ([Session Management CS][owasp-session]). **Analysis**: a longer "remember me" can be a conscious,
     recorded choice.
   - The enumeration choice in [Account enumeration](#account-enumeration-with-an-email-first-sign-in).
4. **Transactional email**: the API sends at least the confirmation link, the reset code, and a password-changed or
   password-reset notice ([Forgot Password CS][owasp-forgot]). It may also send a notice to unknown addresses on
   reset requests.
5. **Contract**: the password maximum must be stated in bytes if bcrypt is kept.

## Sources

Spring (versions pinned by the project):

- [Spring Boot 4.1.1 BOM (`spring-boot-dependencies-4.1.1.pom`)][boot-bom]
- [`spring-security-bom` 7.1.1][ss-bom]
- [`spring-security-core` 7.1.1 jar][ss-core-jar]
- [`spring-security-crypto` 7.1.1 jar][ss-crypto-jar]
- [Spring Security 7.1: What's new][ss-new-71]
- [Spring Security 7.0: What's new][ss-new-70]
- [Spring Security 7.1: Password Storage][ss-pw]
- [Spring Security 7.1: `Argon2PasswordEncoder` Javadoc][ss-argon2-api]
- [Spring Security 7.1: Session Management][ss-session]
- [Spring Security 7.1: One-Time Token Login][ss-ott]
- [Spring Security 7.1: Multi-Factor Authentication][ss-mfa]
- [Spring Security 7.1: Pre-Authentication Scenarios][ss-preauth]
- [Spring Security 7.1: Resource Server JWT][ss-jwt]
- [Spring Security 7.1: Resource Server Opaque Token][ss-opaque]
- [Spring Security 7.1: Bearer Tokens][ss-bearer]
- [Spring Security 7.1: OAuth 2.1 Authorization Server][ss-as]
- [Spring Boot 4.1: Spring Security][boot-sec]
- [Spring Boot 4.1: Running behind a front-end proxy server][boot-proxy]
- [Spring Session 4.1.1: HttpSession integration][spring-session]
- [Spring Session 4.1.1: JDBC PostgreSQL schema][spring-session-schema]

Keycloak:

- [Keycloak 26.8.0 release notes][kc-2680] and [GitHub release][kc-gh-2680]
- [Keycloak: memory and CPU sizing][kc-sizing]
- [Keycloak: running in a container][kc-containers]
- [Keycloak: working with themes][kc-themes]
- [Keycloak: localization][kc-l10n]
- Keycloak 26.8.0 source and docs: [login pt_BR][kc-ptbr-login], [email pt_BR][kc-ptbr-email],
  [forgot password][kc-forgot], [reset-credentials authenticators][kc-resetcred], [UsernameForm][kc-usernameform],
  [AbstractUsernameFormAuthenticator][kc-abstractusername], [brute force][kc-brute]
- [Keycloak: OIDC layers (Direct Access Grants)][kc-oidc-layers]

IETF:

- [RFC 9700: Best Current Practice for OAuth 2.0 Security][rfc9700]
- [RFC 10017: OAuth 2.0 for Browser-Based Applications][rfc10017]

OWASP cheat sheets:

- [Authentication][owasp-authn]
- [Session Management][owasp-session]
- [Forgot Password][owasp-forgot]
- [Credential Stuffing Prevention][owasp-stuffing]
- [Password Storage][owasp-pwstore]
- [JSON Web Token][owasp-jwt]

Other identity providers:

- Ory Kratos: [releases][kratos-gh], [custom UI][kratos-ui], [account recovery][kratos-recovery],
  [security][kratos-security]
- Zitadel: [releases][zitadel-gh], [custom login UI][zitadel-ui]

aulaflix-web (current behaviour):

- [`look-up-email.post.ts`][web-lookup]
- [`sign-up.post.ts`][web-signup]
- [`server/utils/auth.ts`][web-auth-utils]
- [`shared/content/auth.ts`][web-auth-copy]

[ticket]: https://github.com/dev-labs-ai/aulaflix-api/issues/5
[map]: https://github.com/dev-labs-ai/aulaflix-api/issues/1
[boot-bom]: https://repo1.maven.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom
[ss-bom]: https://repo1.maven.org/maven2/org/springframework/security/spring-security-bom/7.1.1/spring-security-bom-7.1.1.pom
[ss-core-jar]: https://repo1.maven.org/maven2/org/springframework/security/spring-security-core/7.1.1/
[ss-crypto-jar]: https://repo1.maven.org/maven2/org/springframework/security/spring-security-crypto/7.1.1/
[ss-new-71]: https://docs.spring.io/spring-security/reference/7.1/whats-new.html
[ss-new-70]: https://docs.spring.io/spring-security/reference/7.0/whats-new.html
[ss-pw]: https://docs.spring.io/spring-security/reference/7.1/features/authentication/password-storage.html
[ss-argon2-api]: https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.html
[ss-session]: https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/session-management.html
[ss-ott]: https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/onetimetoken.html
[ss-mfa]: https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/mfa.html
[ss-preauth]: https://docs.spring.io/spring-security/reference/7.1/servlet/authentication/preauth.html
[ss-jwt]: https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/jwt.html
[ss-opaque]: https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/opaque-token.html
[ss-bearer]: https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/resource-server/bearer-tokens.html
[ss-as]: https://docs.spring.io/spring-security/reference/7.1/servlet/oauth2/authorization-server/index.html
[boot-sec]: https://docs.spring.io/spring-boot/4.1/reference/web/spring-security.html
[boot-proxy]: https://docs.spring.io/spring-boot/4.1/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server
[spring-session]: https://docs.spring.io/spring-session/reference/http-session.html
[spring-session-schema]: https://github.com/spring-projects/spring-session/blob/4.1.1/spring-session-jdbc/src/main/resources/org/springframework/session/jdbc/schema-postgresql.sql
[kc-2680]: https://www.keycloak.org/2026/10/keycloak-2680-released
[kc-gh-2680]: https://github.com/keycloak/keycloak/releases/tag/26.8.0
[kc-sizing]: https://www.keycloak.org/high-availability/multi-cluster/concepts-memory-and-cpu-sizing
[kc-containers]: https://www.keycloak.org/server/containers
[kc-themes]: https://www.keycloak.org/ui-customization/themes
[kc-l10n]: https://www.keycloak.org/ui-customization/localization
[kc-ptbr-login]: https://github.com/keycloak/keycloak/blob/26.8.0/themes/src/main/resources-community/theme/base/login/messages/messages_pt_BR.properties
[kc-ptbr-email]: https://github.com/keycloak/keycloak/blob/26.8.0/themes/src/main/resources-community/theme/base/email/messages/messages_pt_BR.properties
[kc-forgot]: https://github.com/keycloak/keycloak/blob/26.8.0/docs/documentation/server_admin/topics/login-settings/forgot-password.adoc
[kc-resetcred]: https://github.com/keycloak/keycloak/tree/26.8.0/services/src/main/java/org/keycloak/authentication/authenticators/resetcred
[kc-usernameform]: https://github.com/keycloak/keycloak/blob/26.8.0/services/src/main/java/org/keycloak/authentication/authenticators/browser/UsernameForm.java
[kc-abstractusername]: https://github.com/keycloak/keycloak/blob/26.8.0/services/src/main/java/org/keycloak/authentication/authenticators/browser/AbstractUsernameFormAuthenticator.java
[kc-brute]: https://github.com/keycloak/keycloak/blob/26.8.0/docs/documentation/server_admin/topics/threat/brute-force.adoc
[kc-oidc-layers]: https://www.keycloak.org/securing-apps/oidc-layers
[rfc9700]: https://www.rfc-editor.org/rfc/rfc9700.html#section-2.4
[rfc10017]: https://datatracker.ietf.org/doc/rfc10017/
[owasp-authn]: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
[owasp-session]: https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
[owasp-forgot]: https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html
[owasp-stuffing]: https://cheatsheetseries.owasp.org/cheatsheets/Credential_Stuffing_Prevention_Cheat_Sheet.html
[owasp-pwstore]: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
[owasp-jwt]: https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html
[kratos-gh]: https://github.com/ory/kratos/releases
[kratos-ui]: https://www.ory.com/docs/kratos/bring-your-own-ui/custom-ui-overview
[kratos-recovery]: https://www.ory.com/docs/kratos/self-service/flows/account-recovery-password-reset
[kratos-security]: https://www.ory.com/docs/kratos/concepts/security
[zitadel-gh]: https://github.com/zitadel/zitadel/releases
[zitadel-ui]: https://zitadel.com/docs/guides/integrate/login-ui/username-password
[web-lookup]: https://github.com/dev-labs-ai/aulaflix-web/blob/main/server/api/auth/look-up-email.post.ts
[web-signup]: https://github.com/dev-labs-ai/aulaflix-web/blob/main/server/api/auth/sign-up.post.ts
[web-auth-utils]: https://github.com/dev-labs-ai/aulaflix-web/blob/main/server/utils/auth.ts
[web-auth-copy]: https://github.com/dev-labs-ai/aulaflix-web/blob/main/shared/content/auth.ts
