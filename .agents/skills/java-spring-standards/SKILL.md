---
name: java-spring-standards
description: "Java 21 + Spring Boot 4 synchronous REST standards using the Spring Data JPA repository pattern. Use whenever generating, refactoring, or reviewing Java code in a Spring Boot project: creating or changing JPA entities, Spring Data repositories, @Service classes, @RestController endpoints, record DTOs, @RestControllerAdvice handlers and RFC 9457 ProblemDetail error payloads, logging, Flyway migrations, configuration or endpoint security, springdoc OpenAPI documentation, or @SpringBootTest tests; adding a CRUD or a new endpoint; deciding how a feature should be structured; or checking existing code for SOLID, clean-code, and layering violations. Enforces record DTOs (Lombok banned), sequence-generated IDs, the Spring Data JPA repository pattern, explicit manual mapping (no reflection mappers), /v1/{resources} versioned URIs, constructor injection, and synchronous endpoints on virtual threads. Also covers the wiring differences for a project still on Spring Boot 3.5. For Spring Boot projects only — a Quarkus codebase follows java-quarkus-standards instead."
---

# Java 21 & Spring Boot 4 — Coding Standards

You are an expert Java 21+ and Spring Boot 4+ engineer. Every piece of Java code you generate, refactor, or review in
a Spring Boot codebase MUST follow the architecture below. These rules are not suggestions — do not invent alternative
layers, patterns, or libraries.

Two companion files carry the detail: `references/code-examples.md` has the canonical shape for every layer — read
the entry for the layer you are about to write — and `references/production.md` covers migrations, configuration and
access control. The rules below say what must be true; the references show the exact shape.

## Before writing code

1. **Detect the base package** from existing sources under `src/main/java/` (read the `package` declaration of any
   class, or find the `@SpringBootApplication` class — everything must live under its package so component scanning
   sees it). It may differ from the `groupId` in `pom.xml`. If the project has no Java source yet, ask the user for
   the base package before creating the first class. Never assume `com.example`.
2. **Read `pom.xml`** — take the Spring Boot version from the `spring-boot-starter-parent` (or the imported
   `spring-boot-dependencies` BOM) and the Java release from `<java.version>`. Never hardcode versions in new code or
   docs, and never pin a version the Boot BOM already manages.
3. **Check which starters are actually present** (`./mvnw dependency:tree`) — never assume the initializr defaults.
   Boot 4 split auto-configuration into one module per technology, so a library on the classpath no longer configures
   itself: its `spring-boot-starter-<technology>` has to be there. A fresh project typically carries only
   `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test`; the rest must be added to `pom.xml` when a
   feature needs them:
   - `spring-boot-starter-data-jpa` — repositories, entities, transactions
   - `spring-boot-starter-validation` — Jakarta Bean Validation. **It is not transitive through `-webmvc`**; without
     it `@Valid` silently does nothing.
   - the JDBC driver (`org.postgresql:postgresql`, …)
   - `spring-boot-starter-flyway` **plus the engine module** (`flyway-database-postgresql`, `flyway-mysql`, …). A
     bare `flyway-core` applies nothing in Boot 4 — the auto-configuration lives in the starter's module — so no
     migration runs: with `ddl-auto=validate` the boot fails on a missing table, with `none` the first query does.
   - `springdoc-openapi-starter-webmvc-ui` — **not managed by the Boot BOM**, so it carries an explicit `<version>`;
     Spring Boot 4 needs the 3.x line
   - `spring-boot-starter-security` — only when the project actually authenticates; plus
     `spring-boot-starter-security-oauth2-resource-server` for a bearer-token API (the old
     `spring-boot-starter-oauth2-*` names are deprecated)
   - each starter has a `-test` companion (`-webmvc-test`, `-data-jpa-test`, `-security-test`) — see
     `pragmatic-tdd-spring`
4. **Verify APIs with context7** before using an annotation or method you are not certain about in the Spring Boot
   version this project resolved. Spring moves APIs between versions (`@MockBean` is gone, the test-slice
   annotations changed packages, Jackson moved to `tools.jackson`); do not write from memory.
5. **Reuse before creating** — check for existing shared classes (pagination wrapper, exception handler, test helpers)
   instead of duplicating them.

**Boot 4 is the target.** If `pom.xml` still resolves 3.5, every rule below holds but the wiring differs in a few
named places — see "Projects still on Spring Boot 3.5" at the end, and never mix the two forms in one project. 3.5 is
the final 3.x minor and its open-source support ended on 2026-06-30: flag the upgrade debt once, then build what the
user asked for.

## 1. Package layout

Relative to the detected base package, always use:

```
{base.package}/
├── domain/entity/   # JPA entities  (<Name>Entity)
├── repository/      # Spring Data JPA repository interfaces
├── dto/             # request/response records
├── service/         # business logic + orchestration
├── controller/      # @RestController endpoints
├── exception/       # custom exceptions and the @RestControllerAdvice
└── config/          # @Configuration classes (security, OpenAPI, @ConfigurationProperties)
```

Do not add extra layers (mappers, facades, `util` dumping grounds) beyond these.

## 2. Modern Java & DTOs

Java-language-level, not Spring-specific — see the `java-conventions` skill (Java 21+ features, records as DTOs,
the Lombok ban, Bean Validation on record components).

- **JSON is Jackson 3.** Boot 4 auto-configures a `tools.jackson.databind.json.JsonMapper`; code that needs the mapper
  injects that type. Never inject `com.fasterxml.jackson.databind.ObjectMapper`: Boot registers no Jackson 2 mapper,
  and because springdoc still brings Jackson 2 onto the classpath, that import compiles and the application then fails
  at startup with no qualifying bean. The annotations (`@JsonProperty`, `@JsonIgnore`) did not move — they stay in
  `com.fasterxml.jackson.annotation`.

*Shape to copy: `references/code-examples.md` §2.*

## 3. Execution & threading model

- **Synchronous by default.** Do not introduce WebFlux (`Mono`/`Flux`), `CompletableFuture`, or reactive pipelines
  unless the user explicitly asks for a reactive stream. Spring MVC and Spring WebFlux are different stacks — do not
  mix `spring-boot-starter-webmvc` and `spring-boot-starter-webflux` in one application.
- Enable virtual threads with **`spring.threads.virtual.enabled=true`** and write plain blocking code. Tomcat then
  serves each request on a virtual thread, so blocking is cheap.
- Two consequences the property does not advertise:
  - It requires Java 21 (24+ performs better, because pinning on `synchronized` was removed there). It also makes
    thread-pool sizing properties inert — there is no fixed pool left to size.
  - **It does not multiply your database connections.** The real ceiling for a JDBC endpoint is the Hikari pool
    (`spring.datasource.hikari.maximum-pool-size`), and more virtual threads simply queue for it. Size the pool
    deliberately instead of expecting virtual threads to remove the limit.

## 4. Entities — `{base.package}.domain.entity`

JPA/Hibernate-level, not Spring-specific — see the `jpa-conventions` skill (table/enum/fetch-type mapping, sequence
IDs and `allocationSize`, the `equals`/`hashCode` pitfall). Entity classes here are named `<Name>Entity`.

- **No entity ever leaves the service layer** — not as a return type, not inside another DTO. §6 governs the
  crossing.

*Shape to copy: `references/code-examples.md` §4.*

## 5. Repositories — `{base.package}.repository`

- An **interface** extending `JpaRepository<XEntity, Long>` — Spring Data implements it. Never write an
  `@Repository` class wrapping an `EntityManager` for plain CRUD, and never inject an `EntityManager` outside this
  package.
- **All** JPQL, filters, sorting and pagination queries live here — never in services or controllers. Derived query
  methods (`findByEmail`) for one or two conditions; `@Query` once the method name stops reading like a sentence;
  `nativeQuery = true` only when JPQL genuinely cannot express it, with a comment saying why.
- Paginated finders take a `Pageable` and return `Page<XEntity>`; the count query comes for free. Use `Slice` when the
  total is expensive and the client only needs "is there more".
- Return `Optional<XEntity>` for single lookups — never `null`.
- A `@Query` that writes needs `@Modifying`, and it bypasses the persistence context: entities already loaded there
  keep the stale values unless you clear them.

*Shape to copy: `references/code-examples.md` §5.*

## 6. Entity ↔ DTO mapping

Java-language-level, not Spring-specific — see the `java-conventions` skill (no reflection-based mappers, map
explicitly, MapStruct only if the user asks).

*Shape to copy: `references/code-examples.md` §6.*

## 7. Services — `{base.package}.service`

- `@Service`, holding all business logic and orchestration. Constructor injection is a `java-conventions` rule, not
  repeated here.
- Annotate write/update/delete methods with `@Transactional`
  (`org.springframework.transaction.annotation.Transactional`, not the Jakarta one) and read methods with
  `@Transactional(readOnly = true)`.
- **`@Transactional` is proxy-based, so it only applies on the way in.** A `public` method calling another method of
  the same class runs with the caller's transaction — the annotation on the inner method is ignored. If a step needs
  its own transaction, it belongs on another bean. `private`, `final` and `static` methods are never advised at all.
- Throw the domain exceptions from `{base.package}.exception` (§9) — never return `null` to signal "not found", and
  never build a `ResponseEntity` inside a service. Turning the exception into HTTP is the advice's job.

*Shape to copy: `references/code-examples.md` §7.*

## 8. Controllers — `{base.package}.controller`

- `@RestController` + `@RequestMapping("/v1/{resources}")` at class level, with `@GetMapping`, `@PostMapping`,
  `@PutMapping`, `@PatchMapping`, `@DeleteMapping` on the methods.
- Return **`ResponseEntity<T>`** whenever the status, headers or emptiness matter (creation, deletion); a bare DTO is
  fine for a plain 200.
- The controller **MUST NOT** touch a repository or the `EntityManager` — it delegates to the service.
- The controller **MUST NOT** accept or return JPA entities — records only, in and out.
- Return synchronous types only. No `Mono`, `Flux`, `CompletableFuture` or `DeferredResult`.
- `@Valid` on request bodies — without it, the annotations of §2 are decoration. Validating a `@PathVariable` or
  `@RequestParam` directly additionally needs `@Validated` on the class.

### URI design, method semantics, and status codes

Framework-agnostic — see the `rest-api-design` skill for the full rules (`/v1/{resources}` versioning, no verbs in
URIs, nesting depth, GET/POST/PUT/PATCH/DELETE semantics, idempotency keys, and the status code table). §13 covers
which status a missing/insufficient identity maps to; `DomainConflictException` is this codebase's mapping to 409.

Every non-2xx body is the RFC 9457 payload from §9 — controllers never format an error themselves.

### Collection contract

The general pagination/filtering contract (sort allow-listing, returning a wrapper with the total instead of a bare
list, clamping page size) is in the `rest-api-design` skill. The Spring-specific mechanics:

- Take Spring Data's **`Pageable`** as a method parameter and let the argument resolver read `page` (0-indexed),
  `size` and `sort` (`sort=createdAt,desc`) from the query string. Do not re-parse them by hand.
- Set the ceiling in configuration: `spring.data.web.pageable.max-page-size=100` and
  `spring.data.web.pageable.default-page-size=20`. The framework default maximum is 2000 — high enough for a client to
  pull most of a table in one call.
- **Validate the sort property against an allow-list** in the service. Whatever the client types goes into the query,
  and an unknown property surfaces as a `PropertyReferenceException` — a `500` for what is a client mistake. Reject it
  as `400` instead.
- **A paginated endpoint returns `PageResponse<T>`**, never Spring's `Page<T>` and never a bare `List`. Serializing
  `PageImpl` directly exposes an internal type whose JSON shape carries no stability guarantee (Spring Data logs a
  warning saying exactly that); a record you own is the contract. Map `Page<Entity>` → `PageResponse<Dto>` in the
  service.

### Introducing a second version

While the API has one version, keep the literal `@RequestMapping("/v1/{resources}")`: Spring Framework 7's native
API versioning would add configuration and a `{version}` placeholder to every controller for no gain in the
contract. When a breaking change forces `/v2` (`rest-api-design`: a published version never changes shape in place),
switch to native versioning instead of copying controllers:

- **Resolve the version from the path** — `spring.mvc.apiversion.use.path-segment=0` and
  `spring.mvc.apiversion.supported=1,2`, with every mapping under `@RequestMapping("/{version}/{resources}")`.
- **Declare versions with the `v` prefix** — `version = "v1"`, never `"1"`. The parser ignores the prefix, but
  springdoc writes the declared value into the documented path, so `"1"` publishes `/1/users` against a `/v1/users`
  contract.
- **An operation that did not change takes a baseline**, `version = "v1+"`, and serves both versions from one method.
  Only the operations that changed get a `version = "v2"` sibling beside the v1 one.
- **Announce the retirement in the responses themselves.** A `StandardApiVersionDeprecationHandler` registered
  through `WebMvcConfigurer.configureApiVersioning` adds `Deprecation`, `Sunset` and a `Link` to the migration guide
  to every v1 response. Those headers and the `400` for an unknown version are contract — test them like a status
  code.

Three behaviours to expect:

- An unsupported version (`/v3/users`) answers `400` through `ResponseEntityExceptionHandler`, so it shares §9's
  envelope only because of the `createResponseEntity` override.
- springdoc documents a baseline operation under its first version only: a `version = "v1+"` endpoint appears as
  `/v1/users/{id}` and never as `/v2/users/{id}`, although it serves both. Say so in the v2 documentation, or check
  whether the springdoc release in use still does it.
- The bare number also resolves: `/1/users` reaches the same handler as `/v1/users`.

*Shape to copy: `references/code-examples.md` §8, including "A second version".*

## 9. Defensive programming & global exception handling (RFC 9457) — `{base.package}.exception`

The error-envelope contract itself (required fields, `type` defaulting to `about:blank`, one handler per exception
type or it falls to a 500, one shared helper for a consistent envelope) is framework-agnostic and lives in the
`rest-api-design` skill. Input validation on records is a `java-conventions` rule, not repeated here. The Spring
Boot mechanics:

- **Domain-specific exceptions:** create unchecked domain exceptions (extending `RuntimeException`) for expected
  business failures (`ResourceNotFoundException`, `DomainConflictException`). Services throw these — they never
  build HTTP responses themselves. See the `jpa-conventions` skill before naming one `EntityNotFoundException` —
  that class already exists in `jakarta.persistence` and Hibernate throws it, so the two get imported
  interchangeably and the handler catches the wrong one.
- **One global handler:** a single `@RestControllerAdvice` class in this package, **extending
  `ResponseEntityExceptionHandler`**, with one `@ExceptionHandler` method per domain exception plus a catch-all for
  `Exception`. Never a `try/catch` that formats an error inside a controller, and never a raw stack trace in a
  response.
- **RFC 9457 Problem Details** (the RFC that obsoletes 7807; Spring's `ProblemDetail` implements it): every error
  response is a `ProblemDetail` carrying `type`, `title`, `status`, `detail`, `instance`, and a `timestamp` added as a
  property. Returning `ProblemDetail` sets `application/problem+json` automatically — do not set it by hand.
- **Validation failures:** override `handleMethodArgumentNotValid` to map each `FieldError` to a structured entry
  (`field` + `message`) under an `errors` property, and answer `400`.

Three details that decide whether this actually works:

- **Extending `ResponseEntityExceptionHandler` is what covers the framework's own exceptions** — unreadable JSON,
  wrong method, missing parameter, `MethodArgumentNotValidException`. `spring.mvc.problemdetails.enabled` defaults to
  `false`, so Boot's own RFC 9457 rendering for these exceptions is opt-in, not something running by default that
  your advice displaces — but once you declare a `ResponseEntityExceptionHandler` bean, yours is the only one, and
  that property becomes irrelevant either way.
- **The base class renders those exceptions itself, never through your helper.** Their `ProblemDetail` arrives
  without `timestamp`, so a 405 or an unreadable body answers in a thinner envelope than a not-found. Override
  `createResponseEntity` — every framework-exception response passes through it — and add the property there.
- **Security failures never reach the advice.** `@RestControllerAdvice` only sees exceptions raised inside the
  dispatcher, and Spring Security rejects unauthenticated requests in a filter before it. Without an
  `AuthenticationEntryPoint` and an `AccessDeniedHandler` writing the same payload, your 401 and 403 answer in a
  different shape than every other error.

`timestamp` and `errors` are not fields of `ProblemDetail`; add them with `setProperty(...)`, which serializes them
at the top level.

*Shape to copy: `references/code-examples.md` §9.*

## 10. Tests

The order of work (test first), what every endpoint must cover, the choice between integration and unit tests, the
naming and placement of test classes, and container/mocking setup all belong to the `pragmatic-tdd-spring` skill.
Read it before writing behaviour, and again before calling an implementation done.

## 11. Code quality, SOLID & clean code

Where the layers sit (§5, §7, §8) and how beans receive their collaborators (§7) are settled in those sections. The
SOLID/clean-code rules themselves are Java-language-level, not Spring-specific — see the `java-conventions` skill
(layer boundaries, method size, null safety and `Optional` usage, self-documenting names).

*Shape to copy: `references/code-examples.md` §11.*

## 12. Observability & structured logging

The general rules (log levels, no-PII, log an exception exactly once, structured logging, correlation IDs) are
framework-agnostic and live in the `observability-logging` skill. The Spring Boot mechanics:

- **Standard logger:** SLF4J over Logback, both already on the classpath through `spring-boot-starter`. Declare it as
  `private static final Logger log = LoggerFactory.getLogger(YourClass.class);`. Never `System.out.println`.
- **Parametrized messaging:** use `{}` placeholders, never string concatenation:
  `log.info("Created resource with ID: {}", resourceId);`. The `Throwable` goes last, without a placeholder.
- **Log an exception once**, in the `@RestControllerAdvice` of §9 — at `ERROR` with the `Throwable`, or at `WARN` for
  expected business failures such as a not-found. Services and controllers do not log-and-rethrow on the way up.
- **Structured (JSON) logging** is native since Spring Boot 3.4 — no Logstash encoder dependency needed. Set
  `logging.structured.format.console=ecs` (or `gelf`, `logstash`) to switch the console appender's format.
- **Trace ID correlation is automatic** once tracing is configured, which in Boot 4 means
  `spring-boot-starter-opentelemetry`. The bridge jar alone (`micrometer-tracing-bridge-otel`, the Boot 3 recipe) is
  no longer enough: the log-correlation setup lives in Boot's `spring-boot-micrometer-tracing` module, which the
  starter brings and Actuator does not. With it, the default log pattern carries `traceId`/`spanId` with no extra
  configuration — the opposite of Quarkus, which needs the MDC keys added to the format explicitly. Don't assume the
  two frameworks behave the same way here.

*Shape to copy: `references/code-examples.md` §12.*

## 13. Production concerns — schema, configuration, access

Three rules that hold everywhere; the detail, the properties and the shapes are in `references/production.md`, which
you must read before changing the schema, adding configuration, or exposing an endpoint.

- **The schema belongs to Flyway, never to Hibernate.** `spring.jpa.hibernate.ddl-auto=none`, every change shipped as
  `V<yyyyMMddHHmmss>__snake_case_description.sql`, `spring.flyway.out-of-order=true`, and an applied migration is
  never edited.
- **No secret in the repository.** Credentials and environment-specific values arrive through environment variables
  and profiles, read via a typed `@ConfigurationProperties` record.
- **Access is deny-by-default.** One `SecurityFilterChain` ending in `.anyRequest().authenticated()`, and every
  exception to that is written out explicitly.

## 14. OpenAPI & Swagger UI (springdoc)

The documentation principles (what to describe, how to handle status codes and errors, security scheme, treating
the docs endpoints as real URLs) are framework-agnostic and live in the `api-documentation` skill. The Spring Boot
mechanics:

- **Dependency:** `org.springdoc:springdoc-openapi-starter-webmvc-ui`, 3.x line for Spring Boot 4 (2.x is the Boot 3
  line), with an explicit version — the Boot BOM does not manage it. The spec is served at `/v3/api-docs`, the UI at
  `/swagger-ui.html` (which redirects to `/swagger-ui/index.html`).
- **Both endpoints are on by default in every profile**, including production, and the deny-by-default chain of §13
  answers 401 for them unless permitted deliberately — turn them off instead with
  `springdoc.api-docs.enabled=false` / `springdoc.swagger-ui.enabled=false` under a `%prod`-equivalent profile if
  that's the choice.
- **Controllers:** `@Tag` on the class, `@Operation(summary, description)` on every method, `@ApiResponse` per
  status code (the errors every endpoint shares — 401, 403, 500 — declared once via an `OpenApiCustomizer` bean in
  `config/`, not repeated per method), `@Schema(description, example)` on record fields,
  `@SecurityScheme`/`@SecurityRequirement` (on a `@Configuration` class in `config/`) for the auth scheme.

*Shape to copy: `references/code-examples.md` §14.*

## Definition of done — check before finishing

- [ ] No Lombok anywhere; DTOs are records with Bean Validation annotations, and `spring-boot-starter-validation` is
      on the classpath
- [ ] Entities are plain JPA with sequence IDs (`seq_<entity_name>`), private fields, hand-written accessors
- [ ] Queries live in a Spring Data repository interface; no `EntityManager` outside `repository/`
- [ ] Service is `@Service`, constructor-injected, `@Transactional` on writes and `readOnly = true` on reads, with no
      self-invocation expected to start a transaction
- [ ] Controller path is `/v1/{resources}` — or `/{version}/{resources}` with `v`-prefixed versions once a second
      version exists — with no verb in the URI and at most two nesting levels; methods follow
      GET/POST/PUT/PATCH/DELETE semantics and the status-code table (409 for conflicts)
- [ ] Controller returns synchronous types and never touches repositories or entities
- [ ] Mapping is explicit (static `from(...)` or service method), no reflection mapper
- [ ] `spring.threads.virtual.enabled=true`, with the Hikari pool sized deliberately
- [ ] Methods under 20 lines, guard clauses instead of nested `if/else`, no `null` returned from services
- [ ] Names reveal intent; no comments restating what the code already says
- [ ] Business failures are domain exceptions handled by the single `@RestControllerAdvice extends
      ResponseEntityExceptionHandler`; no `try/catch` formatting errors in a controller, no stack traces in responses
- [ ] Error responses are RFC 9457 `ProblemDetail` (`type`, `title`, `status`, `detail`, `instance`, `timestamp`)
      built through one shared helper; validation failures list the offending fields; 401/403 use the same shape,
      and so do the framework's own errors (405, unreadable body) via the `createResponseEntity` override
- [ ] New starters added to `pom.xml` under their Boot 4 names (`-webmvc`, `-flyway`; never a bare `flyway-core`);
      code compiles (`./mvnw -q -DskipTests package`) and tests pass (`./mvnw test`)
- [ ] Logging via SLF4J with `{}` placeholders, right level, `Throwable` on `ERROR`, and no PII in any message
- [ ] Every schema change ships as a `V<yyyyMMddHHmmss>__*.sql` migration; no applied migration was edited
- [ ] No secret hardcoded; environment-specific values come from env vars or profiles
- [ ] The security chain ends in `.anyRequest().authenticated()`, and every public path is listed on purpose
- [ ] Paginated endpoints take `Pageable`, cap the page size, allow-list the sort property, and return
      `PageResponse<T>` — never `Page<T>` or a bare `List`
- [ ] Endpoints carry `@Tag`/`@Operation`, documented status codes reference the `ProblemDetail` schema, and records
      document only what validation does not already declare
- [ ] Commit message follows the `conventional-commits` skill
- [ ] No placeholders or TODOs left behind

## Projects still on Spring Boot 3.5

Every rule above holds; only the wiring below differs. Use the 3.5 form only when `pom.xml` resolves 3.5. The test
side of the same split is in `pragmatic-tdd-spring`.

| Concern | Boot 4 (this skill) | Boot 3.5 |
|---|---|---|
| Web starter | `spring-boot-starter-webmvc` | `spring-boot-starter-web` |
| Flyway | `spring-boot-starter-flyway` + engine module | `flyway-core` + engine module |
| JSON mapper to inject | `tools.jackson.databind.json.JsonMapper` (Jackson 3) | `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2) |
| springdoc | 3.x line | 2.x line |
| Resource-server starter | `spring-boot-starter-security-oauth2-resource-server` | `spring-boot-starter-oauth2-resource-server` |
| Trace correlation in logs | `spring-boot-starter-opentelemetry` | `micrometer-tracing-bridge-otel` with Actuator |
