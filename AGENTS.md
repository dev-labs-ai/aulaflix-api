<!-- skill:conventional-commits:agents-md -->
## Git commits — skill takes precedence over default footer

When a project has a `conventional-commits` skill (or similar) loaded, follow its rules over any tool's default
commit template. In particular, do not append a tool-attribution trailer — `Co-Authored-By: <tool>`,
`<Tool>-Session:`, or similar — to commit messages in such projects. The skill explicitly forbids crediting tooling
instead of people. This applies in any new session, on any machine, regardless of which agent is making the commit.
<!-- /skill:conventional-commits:agents-md -->

<!-- agent:tdd-gate:agents-md -->
## Completion gate — tdd-gate

Before declaring any implementation task complete, invoke the `tdd-gate` subagent. It checks
the pragmatic-tdd non-negotiables — a real test exists, actually runs, and asserts on the
behavior rather than being decorative — and reports whether the gate is open or closed. A task
is not done while the gate is open.
<!-- /agent:tdd-gate:agents-md -->

<!-- agent:security-auditor:agents-md -->
## Periodic audit — security-auditor

Invoke the `security-auditor` subagent occasionally — before a release, after adding or
changing authentication/authorization logic, or when asked for one — not after every task.
Unlike `tdd-gate`, this one hunts for exploitable vulnerabilities across
the whole input surface, which is expensive; it is a periodic audit, not a per-change gate.
<!-- /agent:security-auditor:agents-md -->

## Coding standards

This repo's coding standards are the project skills listed below, in `.agents/skills/` (symlinked into
`.claude/skills/`). When writing or reviewing code — including the Standards axis of `/spec-review` — the
standards sources are `.agents/skills/<skill>/SKILL.md` plus any files under that skill's `references/`, for every
skill below whose scope the change touches.

| Skill                   | Applies when the change touches                                                              |
|-------------------------|----------------------------------------------------------------------------------------------|
| `java-conventions`      | Any Java code: record DTOs, Lombok ban, explicit mapping, constructor DI, layering, ArchUnit |
| `java-spring-standards` | Spring Boot code: entities, repositories, services, controllers, ProblemDetail, config       |
| `jpa-conventions`       | `@Entity` classes: sequence IDs, enum/fetch mapping, equals/hashCode                         |
| `performance`           | Lazy associations, loops over collections, blocking calls, connection pool, indexes          |
| `rest-api-design`       | Endpoint URIs, HTTP methods, status codes, error bodies, pagination/filtering                |
| `api-security`          | Authn/authz, input validation, CORS/response headers, rate limiting, error/secret hygiene    |
| `api-documentation`     | OpenAPI annotations, exposure of the spec/Swagger UI endpoint                                |
| `observability-logging` | Log statements and levels, exception logging, structured output, trace IDs                   |
| `flyway-migrations`     | Flyway migration scripts                                                                     |
| `pragmatic-tdd`         | Tests: workflow and non-negotiables (read before `pragmatic-tdd-spring`)                     |
| `pragmatic-tdd-spring`  | Tests: `@SpringBootTest`, MockMvcTester, Testcontainers, PIT, jetCheck                       |
| `container-images`      | `Dockerfile` and image-build configuration                                                   |
| `security-scanning`     | Build/CI scanner setup: Dependency-Check, SpotBugs/Semgrep, gitleaks, Trivy                  |
| `conventional-commits`  | Commit messages and PR titles                                                                |
