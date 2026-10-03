# Testing Toolbox — setup, containers, mocking, isolation

Companion to `SKILL.md`. Read the section you need; do not paste all of it into a project at once.

## 1. Check the test classpath before writing the first test

Do not assume which test artifacts are available. Resolve it:

```shell
./mvnw dependency:tree -Dscope=test
./mvnw dependency:tree | grep -iE 'starter-.*-test|testcontainers|wiremock|mockito|assertj'
```

**Boot 4 has one test starter per technology.** Each pulls the base `spring-boot-starter-test` (JUnit Jupiter 6,
Spring Test and Spring Boot Test, AssertJ, Hamcrest, Mockito, JSONassert, JsonPath, XMLUnit, Awaitility) plus that
technology's test auto-configuration. The base starter alone configures neither MockMvc nor any slice — confirm the
list on the version this project resolved.

**What usually has to be declared** — add with `<scope>test</scope>` when first needed, after confirming it is absent:

| Need | Artifact |
|---|---|
| `MockMvcTester`, `@AutoConfigureMockMvc`, `@WebMvcTest`, `RestTestClient` | `org.springframework.boot:spring-boot-starter-webmvc-test` |
| `@DataJpaTest`, `@AutoConfigureTestDatabase` | `org.springframework.boot:spring-boot-starter-data-jpa-test` |
| `@WithMockUser`, `jwt()`, and Spring Security inside the MockMvc slices | `org.springframework.boot:spring-boot-starter-security-test` |
| `@RestClientTest`, `MockRestServiceServer` auto-configuration | `org.springframework.boot:spring-boot-starter-restclient-test` |
| `@ServiceConnection` wiring between containers and the context | `org.springframework.boot:spring-boot-testcontainers` |
| `@Testcontainers` / `@Container` JUnit lifecycle | `org.testcontainers:testcontainers-junit-jupiter` |
| The database engine itself | `org.testcontainers:testcontainers-postgresql` (and the JDBC driver at runtime scope) |
| Stubbing external HTTP | `org.wiremock:wiremock-standalone` |

The Boot BOM manages all of these except WireMock, which carries an explicit version — do not pin the others. It
manages Testcontainers 2, whose artifacts all carry the `testcontainers-` prefix: the 1.x names
(`org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`) are not in the BOM any more and fail the build
with a missing version.

**Where the annotations live now** — the packages moved in Boot 4, and an import written from memory points at the
3.x location:

| Annotation | Package |
|---|---|
| `@AutoConfigureMockMvc`, `@WebMvcTest` | `org.springframework.boot.webmvc.test.autoconfigure` |
| `@DataJpaTest` | `org.springframework.boot.data.jpa.test.autoconfigure` |
| `@AutoConfigureTestDatabase` | `org.springframework.boot.jdbc.test.autoconfigure` |
| `@AutoConfigureRestTestClient`, `@AutoConfigureTestRestTemplate` | `org.springframework.boot.resttestclient.autoconfigure` |
| `@RestClientTest` | `org.springframework.boot.restclient.test.autoconfigure` |
| `PostgreSQLContainer` | `org.testcontainers.postgresql` (the `org.testcontainers.containers` one is deprecated) |

Unchanged: `@SpringBootTest` and `@TestConfiguration` (`org.springframework.boot.test.context`), `@ServiceConnection`
(`org.springframework.boot.testcontainers.service.connection`), and everything from `spring-test` — `MockMvcTester`,
`RestTestClient`, `@MockitoBean`.

## 2. Database container

Both options below start the real engine, and Flyway then runs your actual migrations against it — which is half the
value of the test. Pin the image tag (`postgres:16-alpine`), never `latest`.

### Option A: `@TestConfiguration` bean (preferred)

The container becomes a bean, so its lifecycle follows the application context — and the context cache means every
test class sharing this configuration shares the one container.

```java
@TestConfiguration(proxyBeanMethods = false)
public class ContainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {            // org.testcontainers.postgresql — no type parameter in 2.x
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
```

```java
@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfiguration.class)
abstract class AbstractIntegrationTest {
}
```

The same configuration can back `./mvnw spring-boot:test-run` through a `TestApplication` class
(`SpringApplication.from(Application::main).with(ContainersConfiguration.class).run(args)`), so local development and
the test suite provision the database identically.

### Option B: static `@Container` field

```java
@SpringBootTest
@Testcontainers
class UserRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
}
```

Simple, but a `static @Container` starts and stops **per test class**. Across a suite that means a container per
class. Put the field on one abstract base class every integration test extends, or enable Testcontainers reuse
(`withReuse(true)` plus `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`) so the container
survives between classes and runs.

### Repository slices

`@DataJpaTest` carries `@AutoConfigureTestDatabase`, whose default (`replace = NON_TEST`) swaps any datasource that is
not a test one for an embedded database — the exact substitution this project forbids. A `@ServiceConnection`
container counts as a test datasource and is kept, so importing the container configuration is what makes the slice
run on the real engine; leave it out and the slice goes looking for an embedded database instead:

```java
@DataJpaTest
@Import(ContainersConfiguration.class)
class UserRepositoryTest { ... }
```

## 3. Test data isolation

Pick one strategy per test class and stick to it:

- **`@Transactional` on the test class** — the Spring TestContext framework rolls the transaction back after each
  method. It works for `@DataJpaTest` and for `@SpringBootTest` + MockMvc, because the mock request runs on the test
  thread inside that transaction.
  - It does **not** work with `webEnvironment = RANDOM_PORT`: the request is handled by the server in its own
    transaction, which commits regardless.
  - It also hides missing-flush bugs — code that works only because the test's transaction is still open. When a test
    exists to prove persistence really happened, clean explicitly instead.
- **Clean before each test** (works everywhere, explicit):

```java
@BeforeEach
void resetData() {
    userRepository.deleteAll();
    // insert only the fixtures this class needs
}
```

- `@Sql("/fixtures/users.sql")` for a fixed dataset a whole class shares.

Never rely on data created by another test class, and never assert that an auto-generated id has a specific value —
sequences do not reset between tests. Do not reach for `@DirtiesContext` to clean data: it discards the cached
context and reboots the application (§8).

## 4. Mocking

- **`@MockitoBean` / `@MockitoSpyBean`** (`org.springframework.test.context.bean.override.mockito`) replace a bean
  with a Mockito mock or wrap the real one. `@MockBean` and `@SpyBean` were removed in Boot 4. Unlike them,
  `@MockitoBean` is not picked up from a field of a `@Configuration` class: declare it on the test class or a shared
  base class, or at class level as `@MockitoBean(types = {…})`.
- Mock at the boundary: external HTTP clients and third-party gateways. Do **not** mock the repository in an endpoint
  test whose job is to prove the query and the migration work.
- Every distinct set of bean-override declarations creates another entry in the context cache, i.e. another
  application boot. Group tests that need the same overrides in the same class.
- Verify observable behaviour (returned value, persisted state, response body). Reach for `verify()` only when the
  interaction *is* the contract, e.g. "the notification client was called exactly once".
- No mock of a type you own that has no logic — construct the real object instead.

## 5. Authenticating requests

With the deny-by-default chain from `java-spring-standards` §13, an unauthenticated request gets 401 and proves
nothing about the endpoint. Authenticate it — never switch security off for the test profile.

- `@WithMockUser(roles = "ADMIN")` on the method or class. `roles` prepends `ROLE_`, `authorities` does not — mixing
  them up is the usual reason a `@PreAuthorize("hasRole('ADMIN')")` test fails with 403.
- For a resource server, the request post-processors: `mvc.get().uri("/v1/users").with(jwt().authorities(...))`.
- Keep one test per endpoint asserting the 401 (anonymous) and, where roles differ, the 403 — both are contract.
- **`@WebMvcTest` does not load your `SecurityConfiguration`**: it is a `@Configuration` class, not a web-layer
  component, so the slice runs under Boot's default chain. `@Import(SecurityConfiguration.class)` if the slice test is
  meant to say anything about access rules. In Boot 4 even that default chain comes from
  `spring-boot-starter-security-test` — without it the slice runs with no security at all, and every access-rule
  assertion passes or fails for the wrong reason.

## 6. External HTTP

Never let a test reach the real internet. Either stub the endpoint with WireMock and point the client's configuration
property at the stub URL, or use `MockRestServiceServer` (`RestTemplate`, `RestClient`) / the `@RestClientTest` slice.
Assert what your code does with the response — including the failure paths (timeout, 500, malformed body), which are
the ones production will hit.

## 7. Full-stack tests over real HTTP

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(ContainersConfiguration.class)
class UserControllerIT {

    @Autowired
    private RestTestClient client;                       // bound to the running server's random port

    @Test
    void shouldAnswerAnonymousCallerWithProblemDetail() {
        client.get().uri("/v1/users/1").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.timestamp").exists();
    }
}
```

Boot 4 no longer injects an HTTP client into a `RANDOM_PORT` test on its own — `@AutoConfigureRestTestClient` is what
provides `RestTestClient` (both ship with `spring-boot-starter-webmvc-test`). `TestRestTemplate` still exists, behind
`@AutoConfigureTestRestTemplate` and with `spring-boot-restclient` added to the classpath; prefer `RestTestClient` in
new code.

Name these `*IT` and keep them to the critical paths. Two things to know: `@Transactional` does not roll them back
(§3), and **the Spring Boot parent only pre-configures failsafe under `pluginManagement`** — until
`maven-failsafe-plugin` is declared in `<build><plugins>` (no version or executions needed), `*IT` classes never run
at all.

## 8. Making the suite fast

- **The context cache is the whole game.** Test classes with identical configuration — same annotations, same
  properties, same bean overrides — share one boot. Put the shared setup in one abstract base class and extend it
  instead of repeating annotations with small variations.
- What forks a new context: a different set of `@MockitoBean`s, a one-off `@TestPropertySource`, a different set of
  `@Import`s, `@ActiveProfiles`. Each is another application start.
- `@DirtiesContext` evicts the cache entry — use it only when a test genuinely corrupts the context.
- Prefer plain JUnit + Mockito for logic that needs no context: it costs milliseconds instead of a boot.
- One database container for the whole suite (§2), not one per class.

## 9. Mutation testing

See `pragmatic-tdd/references/test-quality-techniques.md` for the concept — coverage proves a line ran, PIT proves
a test would actually catch a bug in it. Wire it with `pitest-maven`, scoped to the packages you want measured
(running it over controllers, entities, or DTOs is noise — target domain/service logic):

```xml
<plugin>
    <groupId>org.pitest</groupId>
    <artifactId>pitest-maven</artifactId>
    <version>1.30.0</version>
    <configuration>
        <targetClasses>
            <param>com.example.domain.*</param>
        </targetClasses>
        <targetTests>
            <param>com.example.domain.*</param>
        </targetTests>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>org.pitest</groupId>
            <artifactId>pitest-junit5-plugin</artifactId>
            <version>1.2.3</version>
        </dependency>
    </dependencies>
</plugin>
```

```shell
./mvnw org.pitest:pitest-maven:mutationCoverage
```

Despite its name, `pitest-junit5-plugin` drives the JUnit Platform, so it runs Boot 4's JUnit 6 tests as well. Run
it over the files you changed, review the surviving mutants in `target/pit-reports/`, and add the assertion that
would have killed each one before considering the task done. Confirm the plugin and `pitest-junit5-plugin`
versions against Maven Central before pinning — do not guess a version, and do not add this plugin to a project
that didn't ask for mutation testing.

## 10. Property-based testing (jetCheck)

Only add this if it isn't already on the test classpath (§1) and either the user asked for property-based testing
or the task specifically calls for it — do not add a new test dependency unprompted. Not jqwik — see
`pragmatic-tdd/SKILL.md`'s "Property-based testing" section for why.

```xml
<dependency>
    <groupId>org.jetbrains</groupId>
    <artifactId>jetCheck</artifactId>
    <version>0.3.0</version>
    <scope>test</scope>
</dependency>
```

jetCheck is a plain library, not a test engine: `PropertyChecker.forAll` runs inside an ordinary JUnit `@Test`, so it
works on any JUnit version and needs no Spring context for a pure-domain property. Use it when a function has a real
invariant (see `pragmatic-tdd/SKILL.md`'s "Property-based testing" section), not as a blanket replacement for the
example-based tests in Step 1. Confirm the version against Maven Central before pinning — do not guess it:

```java
class AccountPropertyTest {

    // Dependent ranges are drawn inside one generator, so every scenario already satisfies its precondition.
    private static final Generator<Scenario> SCENARIOS = Generator.from(data -> {
        int overdraftLimitCents = data.generate(Generator.integers(0, 1_000_000));
        int initialBalanceCents = data.generate(Generator.integers(-overdraftLimitCents, 1_000_000));
        int amountCents = data.generate(Generator.integers(1, 1_000_000));
        return new Scenario(overdraftLimitCents, initialBalanceCents, amountCents);
    });

    @Test
    void depositThenWithdrawSameAmountPreservesBalance() {
        PropertyChecker.forAll(SCENARIOS, scenario -> {
            Account account = new Account(UUID.randomUUID(), scenario.initialBalanceCents(),
                    scenario.overdraftLimitCents());
            account.deposit(scenario.amountCents());
            account.withdraw(scenario.amountCents());
            return account.getBalanceCents() == scenario.initialBalanceCents();
        });
    }

    private record Scenario(int overdraftLimitCents, int initialBalanceCents, int amountCents) {}
}
```

What differs from an annotation-driven library:

- **The property is a `Predicate`.** Returning `false` falsifies it, and so does anything thrown inside the lambda —
  an AssertJ assertion included, so the familiar assertions still work there.
- **A failure is shrunk and reproducible.** `PropertyFalsified` prints the shrunk input — a record's `toString`
  makes it readable, e.g. `On Scenario[overdraftLimitCents=0, initialBalanceCents=0, amountCents=1]` — plus a
  `PropertyChecker.customized().rechecking("…")` call that replays it. Treat the shrunk case as small, not
  guaranteed minimal, and turn it into an ordinary example-based test as the regression test.
- **Generators are composed in code**, not declared with annotations: `Generator.integers(min, max)`, `stringsOf`,
  `listsOf`, `sampledFrom`, and `Generator.from` for anything built from several draws. There is no `long`
  generator — draw an `int` range and widen it when the domain needs more.

## 11. Projects still on Spring Boot 3.5

Everything above holds; only the wiring below differs. Use the 3.5 form only when `pom.xml` resolves 3.5 — the
production-code side of the same split is in `java-spring-standards`.

| Concern | Boot 4 (this file) | Boot 3.5 |
|---|---|---|
| Test dependencies | per-technology `-test` starters (§1) | `spring-boot-starter-test`, plus `spring-security-test` |
| Annotation packages | §1 | `org.springframework.boot.test.autoconfigure.*` — `web.servlet` (`@AutoConfigureMockMvc`, `@WebMvcTest`), `orm.jpa` (`@DataJpaTest`), `jdbc` (`@AutoConfigureTestDatabase`), `web.client` (`@RestClientTest`) |
| Testcontainers | 2.x: `testcontainers-postgresql`, `org.testcontainers.postgresql.PostgreSQLContainer` | 1.x: `org.testcontainers:postgresql`, `org.testcontainers.containers.PostgreSQLContainer<?>` |
| Full-stack HTTP client | `RestTestClient` via `@AutoConfigureRestTestClient` | `TestRestTemplate`, injected with no extra annotation |
| `@MockBean` / `@SpyBean` | removed | deprecated — still use `@MockitoBean` |
| JUnit | Jupiter 6 | Jupiter 5 |
