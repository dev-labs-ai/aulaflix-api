package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminApi.tokenOf;
import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.AccountService;

class SessionControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    private StudentApi students;

    @BeforeEach
    void useTheStudentApi() {
        students = new StudentApi(mvc);
    }

    /** A browser that solves the CAPTCHA its IP's soft limit asks for past 10 sign-ins within 15 minutes. */
    private StudentApi solvingCaptchas() {
        return new StudentApi(new BffApi(mvc).solvingCaptchas());
    }

    @Test
    void signsInWithTheRightCredentialsForThirtyDays() {
        String email = newEmail();
        String signUpToken = students.signedUp(email, PASSWORD);
        clock.set(Instant.parse("2026-10-05T12:00:00Z"));

        MvcTestResult result = students.signIn(" " + email.toUpperCase() + " ", PASSWORD);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "/v1/sessions/current")
                .bodyJson().extractingPath("$.expiresAt").isEqualTo("2026-11-04T12:00:00Z");
        String token = tokenOf(result);
        assertThat(token).isNotEqualTo(signUpToken);
        assertThat(students.account(token)).hasStatusOk()
                .bodyJson().extractingPath("$.email").isEqualTo(email);
    }

    @Test
    void signsInWithAPasswordOfExactlySeventyTwoBytes() {
        String email = newEmail();
        String longestPassword = "é".repeat(36);
        students.signedUp(email, longestPassword);

        assertThat(students.signIn(email, longestPassword)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesAWrongPasswordAnUnknownEmailAndAnAdminWithTheSameBody() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        String admin = "admin-" + newEmail();
        accounts.createAdmin(admin, "Ana", PASSWORD);

        MvcTestResult wrongPassword = students.signIn(email, PASSWORD + "!");
        MvcTestResult unknownEmail = students.signIn(newEmail(), PASSWORD);
        MvcTestResult adminCredentials = students.signIn(admin, PASSWORD);

        assertThat(wrongPassword).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-credentials",
                          "title": "Invalid credentials",
                          "status": 400,
                          "detail": "The email or the password is wrong.",
                          "instance": "/v1/sessions",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
        assertThat(body(unknownEmail)).isEqualTo(body(wrongPassword));
        assertThat(body(adminCredentials)).isEqualTo(body(wrongPassword));
    }

    @Test
    void refusesMissingFieldsAsAnInvalidRequestListingEachOne() {
        MvcTestResult result = new BffApi(mvc).post("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \" \"}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "One or more fields are invalid.",
                          "instance": "/v1/sessions",
                          "timestamp": "%s",
                          "errors": [
                            {"field": "email", "code": "required"},
                            {"field": "password", "code": "required"}
                          ]
                        }""".formatted(clock.instant()));
    }

    /** The sign-in password has no minimum, only bcrypt's maximum, which no stored password exceeds. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFields")
    void refusesAnInvalidFieldWithItsCode(String description, String email, String password, String field,
                                          String code) {
        assertThat(students.signIn(email, password)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "%s", "code": "%s"}]
                        }""".formatted(field, code));
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(
                Arguments.of("email without a dot after @", "bia@aulaflix", "x", "email", "invalid-email"),
                Arguments.of("password of 73 UTF-8 bytes", newEmail(), "\u00e9".repeat(36) + "a", "password",
                        "too-long"));
    }

    @Test
    void blocksAnEmailForFifteenMinutesAfterTenFailuresEvenWithTheRightPassword() {
        students = solvingCaptchas();
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        Instant start = Instant.parse("2026-10-05T12:00:00Z");
        for (int failure = 0; failure < 10; failure++) {
            clock.set(start.plus(Duration.ofMinutes(failure)));
            assertThat(students.signIn(email, "wrong password")).hasStatus(HttpStatus.BAD_REQUEST);
        }
        Instant blocked = start.plus(Duration.ofMinutes(9));

        clock.set(blocked.plusMillis(90_500));
        MvcTestResult duringTheBlock = students.signIn(email, PASSWORD);

        assertThat(duringTheBlock).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, "810")
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/sign-in-blocked",
                          "title": "Sign-in blocked",
                          "status": 429,
                          "detail": "Too many failed sign-ins for this email. Try again later.",
                          "instance": "/v1/sessions",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
        clock.set(blocked.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertThat(students.signIn(email, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        clock.set(blocked.plus(Duration.ofMinutes(15)));
        assertThat(students.signIn(email, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void blocksAnEmailWithoutAnAccountToo() {
        students = solvingCaptchas();
        String email = newEmail();
        for (int failure = 0; failure < 10; failure++) {
            assertThat(students.signIn(email, PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST);
        }

        assertThat(students.signIn(email, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, "900");
    }

    @Test
    void neverCountsAStudentsFailuresAgainstAnAdminNorTheOtherWayRound() {
        students = solvingCaptchas();
        String admin = "admin-" + newEmail();
        accounts.createAdmin(admin, "Ana", PASSWORD);
        String student = newEmail();
        students.signedUp(student, PASSWORD);
        AdminApi admins = new AdminApi(mvc);
        for (int failure = 0; failure < 10; failure++) {
            students.signIn(admin, "wrong password");
            admins.signIn(student, "wrong password");
        }

        assertThat(students.signIn(admin, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(admins.signIn(admin, PASSWORD)).hasStatus(HttpStatus.CREATED);
        assertThat(admins.signIn(student, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(students.signIn(student, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void endsASessionAfterSevenDaysWithoutUse() {
        String email = newEmail();
        Instant signedIn = Instant.parse("2026-10-05T12:00:00Z");
        clock.set(signedIn);
        String unused = students.signedUp(email, PASSWORD);
        String usedJustInTime = students.sessionToken(email, PASSWORD);

        clock.set(signedIn.plus(Duration.ofDays(7)).minusSeconds(1));
        assertThat(students.account(usedJustInTime)).hasStatusOk();
        clock.set(signedIn.plus(Duration.ofDays(7)));
        assertUnauthenticated(students.account(unused), "/v1/account");
        assertThat(students.account(usedJustInTime)).hasStatusOk();
    }

    @Test
    void endsASessionThirtyDaysAfterSignInDespiteSteadyUse() {
        String email = newEmail();
        Instant signedIn = Instant.parse("2026-10-05T12:00:00Z");
        clock.set(signedIn);
        String token = students.sessionToken(signedUp(email), PASSWORD);

        for (Duration elapsed = Duration.ofDays(6); elapsed.toDays() < 30; elapsed = elapsed.plusDays(6)) {
            clock.set(signedIn.plus(elapsed));
            assertThat(students.account(token)).hasStatusOk();
        }
        clock.set(signedIn.plus(Duration.ofDays(30)).minusSeconds(1));
        assertThat(students.account(token)).hasStatusOk();
        clock.set(signedIn.plus(Duration.ofDays(30)));
        assertUnauthenticated(students.account(token), "/v1/account");
    }

    @Test
    void signOutEndsOnlyTheCurrentSession() {
        String email = newEmail();
        String current = students.signedUp(email, PASSWORD);
        String other = students.sessionToken(email, PASSWORD);

        assertThat(students.signOut(current)).hasStatus(HttpStatus.NO_CONTENT).body().isEmpty();

        assertUnauthenticated(students.signOut(current), "/v1/sessions/current");
        assertUnauthenticated(students.account(current), "/v1/account");
        assertThat(students.account(other)).hasStatusOk();
    }

    @Test
    void refusesSignOutWithoutASession() {
        assertUnauthenticated(new BffApi(mvc).delete("/v1/sessions/current").exchange(), "/v1/sessions/current");
    }

    @Test
    void refusesToSignAnAdminOut() {
        String admin = "admin-" + newEmail();
        accounts.createAdmin(admin, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(admin, PASSWORD);

        assertThat(students.signOut(adminToken)).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/forbidden");
        assertThat(new AdminApi(mvc).signOut(adminToken)).hasStatus(HttpStatus.NO_CONTENT);
    }

    private String signedUp(String email) {
        students.signedUp(email, PASSWORD);
        return email;
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
}
