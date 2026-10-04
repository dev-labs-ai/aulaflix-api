package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminApi.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.service.AccountService;

class AdminSessionControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    private AdminApi adminApi;

    @BeforeEach
    void useTheAdminApi() {
        adminApi = new AdminApi(mvc);
    }

    @Test
    void signsInWithTheRightCredentialsForEightHours() {
        String email = newAdmin();
        clock.set(Instant.parse("2026-10-04T12:00:00Z"));

        MvcTestResult result = adminApi.signIn(" " + email.toUpperCase() + " ", PASSWORD);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "/v1/admin/sessions/current")
                .bodyJson().extractingPath("$.expiresAt").isEqualTo("2026-10-04T20:00:00Z");
        String token = tokenOf(result);
        assertThat(Base64.getUrlDecoder().decode(token)).hasSizeGreaterThanOrEqualTo(32);
        assertThat(adminApi.sessionToken(email, PASSWORD)).isNotEqualTo(token);
    }

    @Test
    void signsInWithAPasswordOfExactlySeventyTwoBytes() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        String longestPassword = "\u00e9".repeat(36);
        accounts.createAdmin(email, "Ana", longestPassword);

        assertThat(adminApi.signIn(email, longestPassword)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void storesTheTokenOnlyAsAHash() {
        String email = newAdmin();

        String token = adminApi.sessionToken(email, PASSWORD);

        assertThat(jdbc.queryForObject("""
                select count(*) from sessions s join accounts a on a.id = s.account_id
                where a.email = ?""", Long.class, email)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from sessions s where strpos(s::text, ?) > 0",
                Long.class, token)).isZero();
    }

    @Test
    void refusesAWrongPasswordAnUnknownEmailAndAStudentWithTheSameBody() {
        String email = newAdmin();
        String student = "student-" + UUID.randomUUID() + "@aulaflix.com.br";
        new StoredAccounts(jdbc).insertStudent(student, PASSWORD);

        MvcTestResult wrongPassword = adminApi.signIn(email, PASSWORD + "!");
        MvcTestResult unknownEmail = adminApi.signIn("nobody-" + UUID.randomUUID() + "@aulaflix.com.br", PASSWORD);
        MvcTestResult studentCredentials = adminApi.signIn(student, PASSWORD);

        assertThat(wrongPassword).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-credentials",
                          "title": "Invalid credentials",
                          "status": 400,
                          "detail": "The email or the password is wrong.",
                          "instance": "/v1/admin/sessions",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
        assertThat(body(unknownEmail)).isEqualTo(body(wrongPassword));
        assertThat(body(studentCredentials)).isEqualTo(body(wrongPassword));
    }

    @Test
    void refusesMissingFieldsAsAnInvalidRequestListingEachOne() {
        MvcTestResult result = mvc.post().uri("/v1/admin/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \" \"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "One or more fields are invalid.",
                          "instance": "/v1/admin/sessions",
                          "timestamp": "%s",
                          "errors": [
                            {"field": "email", "code": "required"},
                            {"field": "password", "code": "required"}
                          ]
                        }""".formatted(clock.instant()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"email\": ", "", "[]", "{\"email\": {}, \"password\": \"x\"}"})
    void refusesAMalformedBodyAsAnInvalidRequestWithoutErrors(String body) {
        MvcTestResult result = mvc.post().uri("/v1/admin/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "The body is not the JSON this endpoint expects.",
                          "instance": "/v1/admin/sessions",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFields")
    void refusesAnInvalidFieldWithItsCode(String description, String email, String password, String field,
                                          String code) {
        MvcTestResult result = adminApi.signIn(email, password);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "%s", "code": "%s"}]
                        }""".formatted(field, code));
    }

    static Stream<Arguments> invalidFields() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        return Stream.of(
                Arguments.of("password of 73 UTF-8 bytes", email, "\u00e9".repeat(36) + "a", "password", "too-long"),
                Arguments.of("email without a dot after @", "admin@aulaflix", PASSWORD, "email", "invalid-email"),
                Arguments.of("email of 255 characters", "a".repeat(239) + "@aulaflix.com.br", PASSWORD, "email",
                        "too-long"));
    }

    @Test
    void signOutEndsOnlyTheCurrentSession() {
        String email = newAdmin();
        String current = adminApi.sessionToken(email, PASSWORD);
        String other = adminApi.sessionToken(email, PASSWORD);

        assertThat(adminApi.signOut(current)).hasStatus(HttpStatus.NO_CONTENT).body().isEmpty();

        assertUnauthenticated(adminApi.signOut(current), "/v1/admin/sessions/current");
        assertThat(adminApi.signOut(other)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer ", "Bearer q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s", "Bearer a b",
            "bearer q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s", "Basic YWRtaW46c2VjcmV0"})
    void refusesSignOutWithoutAKnownBearerToken(String authorization) {
        MvcTestResult result = mvc.delete().uri("/v1/admin/sessions/current")
                .headers(headers -> {
                    if (!authorization.isEmpty()) {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .exchange();

        assertUnauthenticated(result, "/v1/admin/sessions/current");
    }

    @Test
    void refusesAnUnknownTokenEvenWhereNoSessionIsNeeded() {
        String email = newAdmin();

        MvcTestResult result = mvc.post().uri("/v1/admin/sessions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}""".formatted(email, PASSWORD))
                .exchange();

        assertUnauthenticated(result, "/v1/admin/sessions");
    }

    @Test
    void endsASessionAfterThirtyMinutesWithoutUse() {
        String email = newAdmin();
        Instant signedIn = Instant.parse("2026-10-04T12:00:00Z");
        clock.set(signedIn);
        String unused = adminApi.sessionToken(email, PASSWORD);
        String usedJustInTime = adminApi.sessionToken(email, PASSWORD);

        clock.set(signedIn.plus(Duration.ofMinutes(30)).minusSeconds(1));
        assertThat(use(usedJustInTime)).hasStatusOk();
        clock.set(signedIn.plus(Duration.ofMinutes(30)));
        assertUnauthenticated(use(unused), "/v3/api-docs/admin");
    }

    @Test
    void keepsASessionAliveWhileItIsUsedWithinThirtyMinutes() {
        String email = newAdmin();
        Instant signedIn = Instant.parse("2026-10-04T12:00:00Z");
        clock.set(signedIn);
        String token = adminApi.sessionToken(email, PASSWORD);

        clock.set(signedIn.plus(Duration.ofMinutes(20)));
        assertThat(use(token)).hasStatusOk();
        clock.set(signedIn.plus(Duration.ofMinutes(45)));
        assertThat(use(token)).hasStatusOk();
        clock.set(signedIn.plus(Duration.ofMinutes(75)));
        assertUnauthenticated(use(token), "/v3/api-docs/admin");
    }

    @Test
    void endsASessionEightHoursAfterSignInDespiteSteadyUse() {
        String email = newAdmin();
        Instant signedIn = Instant.parse("2026-10-04T12:00:00Z");
        clock.set(signedIn);
        String token = adminApi.sessionToken(email, PASSWORD);

        for (Duration elapsed = Duration.ofMinutes(20); elapsed.toHours() < 8; elapsed = elapsed.plusMinutes(20)) {
            clock.set(signedIn.plus(elapsed));
            assertThat(use(token)).hasStatusOk();
        }
        clock.set(signedIn.plus(Duration.ofHours(8)).minusSeconds(1));
        assertThat(use(token)).hasStatusOk();
        clock.set(signedIn.plus(Duration.ofHours(8)));
        assertUnauthenticated(use(token), "/v3/api-docs/admin");
    }

    /** The last use is written at most once a minute, so a use within a minute of it does not move the idle end. */
    @Test
    void recordsAUseOnlyAMinuteAfterTheLastOneRecorded() {
        String email = newAdmin();
        Instant signedIn = Instant.parse("2026-10-04T12:00:00Z");
        clock.set(signedIn);
        String usedTooSoon = adminApi.sessionToken(email, PASSWORD);
        String usedAMinuteLater = adminApi.sessionToken(email, PASSWORD);

        clock.set(signedIn.plusSeconds(59));
        assertThat(use(usedTooSoon)).hasStatusOk();
        clock.set(signedIn.plusSeconds(60));
        assertThat(use(usedAMinuteLater)).hasStatusOk();

        clock.set(signedIn.plus(Duration.ofMinutes(30)));
        assertUnauthenticated(use(usedTooSoon), "/v3/api-docs/admin");
        assertThat(use(usedAMinuteLater)).hasStatusOk();
    }

    @Test
    void blocksAnEmailForFifteenMinutesAfterTenFailuresEvenWithTheRightPassword() {
        String email = newAdmin();
        Instant start = Instant.parse("2026-10-04T12:00:00Z");
        for (int failure = 0; failure < 10; failure++) {
            clock.set(start.plus(Duration.ofMinutes(failure)));
            assertThat(adminApi.signIn(email, "wrong password")).hasStatus(HttpStatus.BAD_REQUEST);
        }
        Instant blocked = start.plus(Duration.ofMinutes(9));

        clock.set(blocked.plusMillis(90_500));
        MvcTestResult duringTheBlock = adminApi.signIn(email, PASSWORD);

        assertThat(duringTheBlock).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, "810")
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/sign-in-blocked",
                          "title": "Sign-in blocked",
                          "status": 429,
                          "detail": "Too many failed sign-ins for this email. Try again later.",
                          "instance": "/v1/admin/sessions",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
        clock.set(blocked.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertThat(adminApi.signIn(email, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, "1");
        clock.set(blocked.plus(Duration.ofMinutes(15)));
        assertThat(adminApi.signIn(email, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void blocksAnEmailWithoutAnAccountToo() {
        String email = "nobody-" + UUID.randomUUID() + "@aulaflix.com.br";
        for (int failure = 0; failure < 10; failure++) {
            assertThat(adminApi.signIn(email, PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST);
        }

        assertThat(adminApi.signIn(email, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, "900");
    }

    @Test
    void allowsNineFailures() {
        String email = newAdmin();
        for (int failure = 0; failure < 9; failure++) {
            assertThat(adminApi.signIn(email, "wrong password")).hasStatus(HttpStatus.BAD_REQUEST);
        }

        assertThat(adminApi.signIn(email, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void countsOnlyTheFailuresOfTheLastFifteenMinutes() {
        String expiring = newAdmin();
        String counted = newAdmin();
        Instant start = Instant.parse("2026-10-04T12:00:00Z");
        clock.set(start);
        for (int failure = 0; failure < 9; failure++) {
            adminApi.signIn(expiring, "wrong password");
            adminApi.signIn(counted, "wrong password");
        }

        clock.set(start.plus(Duration.ofMinutes(15)).minusSeconds(1));
        adminApi.signIn(counted, "wrong password");
        clock.set(start.plus(Duration.ofMinutes(15)));
        adminApi.signIn(expiring, "wrong password");

        assertThat(adminApi.signIn(expiring, PASSWORD)).hasStatus(HttpStatus.CREATED);
        assertThat(adminApi.signIn(counted, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    private void assertUnauthenticated(MvcTestResult result, String path) {
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/unauthenticated",
                          "title": "Unauthenticated",
                          "status": 401,
                          "detail": "This needs a valid session token, sent as Authorization: Bearer.",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(path, clock.instant()));
    }

    /** Any request that carries the token uses the session; reading the API document leaves everything else as is. */
    private MvcTestResult use(String token) {
        return mvc.get().uri("/v3/api-docs/admin")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private String newAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        return email;
    }
}
