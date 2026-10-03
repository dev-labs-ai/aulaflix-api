---
name: pragmatic-tdd-spring
description: "Pragmatic TDD / test-first workflow and testing practices for Spring Boot 4 projects (with the differences for a project still on 3.5). Use whenever implementing or changing an endpoint, business service, or repository behaviour in a Spring Boot codebase; when fixing a bug; when asked to add a feature, 'write the tests', set up Testcontainers, mock a bean, set up mutation or property-based testing, or speed up a slow suite; and before declaring any implementation task complete. Defines the concrete test tooling for Spring Boot: the per-technology test starters, @SpringBootTest, MockMvcTester, RestTestClient, @MockitoBean, @WithMockUser, Testcontainers @ServiceConnection, PIT (pitest-maven) for mutation testing, and jetCheck for property-based testing. The workflow and discipline (contract-first, the verification cycle, non-negotiables) are framework-agnostic and live in the pragmatic-tdd skill — read that first. Complements the java-spring-standards skill, which defines how the code itself must be shaped. For Spring Boot projects only — a Quarkus codebase follows pragmatic-tdd-quarkus instead."
---

# Pragmatic TDD & Test-First — Spring Boot

The workflow (contract-first, write/implement/verify, non-negotiables) is framework-agnostic and lives in the
`pragmatic-tdd` skill — read it first. This file covers the Spring Boot-specific tooling that workflow runs on.

## Applying the cycle

**Step 1 — write the failing test.**

- One test class per controller, mirroring the production package under `src/test/java`, named `<Controller>Test`.
- Boot 4 moved the slice and MockMvc annotations into per-technology modules — `@AutoConfigureMockMvc` is
  `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc` and needs `spring-boot-starter-webmvc-test`.
  `references/testing-toolbox.md` §1 lists where each annotation lives and which test starter brings it.

```java
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvcTester mvc;                       // MockMvc is auto-configured too; AssertJ picks this one

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldCreateUser() {
        assertThat(mvc.post().uri("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Ada", "email": "ada@example.com"}
                        """))
                .hasStatus(HttpStatus.CREATED)
                .matches(header().exists("Location"))
                .bodyJson().extractingPath("$.email").isEqualTo("ada@example.com");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void shouldRejectBlankName() {
        assertThat(mvc.post().uri("/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "", "email": "ada@example.com"}
                        """))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors[0].field").isEqualTo("name");
    }

    @Test
    void shouldRejectAnonymousCaller() {                 // no @WithMockUser at all — a genuinely anonymous request
        assertThat(mvc.get().uri("/v1/users")).hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
```

**Step 2 — implement.** Write the smallest Controller/Service/Repository code that satisfies the assertions, following
the layering and conventions in the `java-spring-standards` skill. No speculative endpoints, fields, or config that
no test asks for.

**Step 3 — verify in the terminal.**

```shell
./mvnw test           # unit + @SpringBootTest classes
./mvnw verify         # adds failsafe (*IT) runs when the plugin is configured
```

## Choosing the kind of test

| Kind | Use it for | Cost |
|---|---|---|
| `@SpringBootTest` + `@AutoConfigureMockMvc` (**default**) | Every endpoint: status codes, payloads, validation, auth, error mapping | One context per distinct configuration, cached across classes |
| `@WebMvcTest` + `@MockitoBean` | Controller-only concerns (mapping, validation, serialization) when the service is genuinely uninteresting | Faster context, but proves nothing about persistence |
| `@DataJpaTest` + Testcontainers | Repository queries: a hand-written `@Query`, a projection, a native statement | Import the container configuration — without a `@ServiceConnection` datasource the slice swaps in an embedded database |
| Plain JUnit + Mockito (no Spring) | Pure functions and branching business rules: mappers, validators, value objects | Milliseconds — prefer it whenever no context is needed |
| jetCheck `PropertyChecker.forAll` (no Spring context) | Functions with a real invariant — see `pragmatic-tdd`'s "Property-based testing" | Same tier as plain JUnit, runs more inputs per test |
| `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@AutoConfigureRestTestClient` (`*IT`) | Smoke-testing over a real HTTP stack, run by `./mvnw verify` | Slow; a handful of critical paths only |

## Real dependencies, never fakes

The database in tests is the real engine production uses, in a container — see the `pragmatic-tdd` skill for why
(here, it also means Flyway migrations written for Postgres get exercised for real, not skipped because H2 accepted
different SQL). `@ServiceConnection` wires the container into the context; see `references/testing-toolbox.md` for
the full setup, the dependencies to add, and the code.

External HTTP services are never called for real in tests — stub them (WireMock) or mock the client bean.

**Authentication in tests uses `@WithMockUser`** (or `jwt()`/`SecurityMockMvcRequestPostProcessors` for a token-based
setup) — `@WithMockUser(roles = "ADMIN")` for an authenticated request. `@WithMockUser` needs
`spring-boot-starter-security-test`: with only the bare `spring-security-test` artifact, Boot 4 no longer hands its
security context to MockMvc and the request arrives anonymous — a 401 where the test expected 201. For the
unauthenticated 401 case, don't add it at all: a plain test method with no security annotation makes a genuinely
anonymous request through the real security filter chain.

## Reference

`references/testing-toolbox.md` — the test starters and where each annotation lives, Testcontainers wiring via
`@ServiceConnection`, transactional rollback and data isolation, `@MockitoBean`, authenticating requests, stubbing
external HTTP, mutation testing with PIT, property-based testing with jetCheck, and why the context cache decides how
fast the suite runs. Read it before setting up the first test class of a new kind (first DB test, first mocked bean,
first external integration, first mutation-testing run). Its last section lists what differs on a project still on
Boot 3.5.
