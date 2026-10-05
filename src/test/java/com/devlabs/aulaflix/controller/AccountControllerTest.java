package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.tokenOf;
import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Hibp;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.AccountService;

class AccountControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private Hibp hibp;

    private StudentApi students;

    @BeforeEach
    void useTheStudentApi() {
        students = new StudentApi(mvc);
    }

    @Test
    void signsUpAndSignsTheStudentInForThirtyDays() {
        String email = newEmail();
        clock.set(Instant.parse("2026-10-05T12:00:00Z"));

        MvcTestResult result = students.signUp("  Bia \t Souza ", " " + email.toUpperCase() + "  ", PASSWORD);

        assertThat(result).hasStatus(HttpStatus.CREATED)
                .hasHeader("Location", "/v1/account")
                .bodyJson().extractingPath("$.expiresAt").isEqualTo("2026-11-04T12:00:00Z");
        assertThat(students.account(tokenOf(result))).hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"name": "Bia Souza", "email": "%s", "emailConfirmed": false}""".formatted(email));
    }

    @Test
    void looksUpAnEmailInAnyLetterCaseOrSpacing() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);

        assertThat(students.lookUp("  " + email.toUpperCase() + " ")).hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"exists": true}""");
        assertThat(students.lookUp(newEmail())).hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"exists": false}""");
    }

    @Test
    void looksUpAnAdminsEmailAsAnyOtherTakenEmail() {
        String email = "admin-" + newEmail();
        accounts.createAdmin(email, "Ana", PASSWORD);

        assertThat(students.lookUp(email)).hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"exists": true}""");
    }

    @ParameterizedTest
    @CsvSource({"' ', required", "bia@aulaflix, invalid-email", "bia maria@aulaflix.com.br, invalid-email"})
    void refusesToLookUpAnInvalidEmail(String email, String code) {
        assertThat(students.lookUp(email)).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "One or more fields are invalid.",
                          "instance": "/v1/account-lookups",
                          "timestamp": "%s",
                          "errors": [{"field": "email", "code": "%s"}]
                        }""".formatted(clock.instant(), code));
    }

    @Test
    void refusesToLookUpWithoutAnEmail() {
        MvcTestResult result = new BffApi(mvc).post("/v1/account-lookups")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "email", "code": "required"}]
                        }""");
    }

    @Test
    void refusesASignUpWithATakenEmailInAnyLetterCaseOrSpacing() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);

        MvcTestResult result = students.signUp("Outra Bia", " " + email.toUpperCase() + "  ", PASSWORD);

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/email-taken",
                          "title": "Email taken",
                          "status": 409,
                          "detail": "An Account with this email already exists: sign in with its password.",
                          "instance": "/v1/accounts",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }

    /**
     * The email is checked before the breach check and bcrypt, so sign-ups sent at once all pass that check; the
     * unique email decides which one wins.
     */
    @Test
    void createsOneAccountWhenTheSameEmailSignsUpSeveralTimesAtOnce() {
        String email = newEmail();
        int signUps = 4;
        StudentApi browser = new StudentApi(new BffApi(mvc).solvingCaptchas());

        List<Integer> statuses;
        try (ExecutorService browsers = Executors.newFixedThreadPool(signUps)) {
            List<CompletableFuture<Integer>> answers = IntStream.range(0, signUps)
                    .mapToObj(signUp -> CompletableFuture.supplyAsync(
                            () -> browser.signUp("Bia", email, PASSWORD).getResponse().getStatus(), browsers))
                    .toList();
            statuses = answers.stream().map(CompletableFuture::join).toList();
        }

        assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED.value(), HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.value());
    }

    /** A taken email is told first, so the Student signs in rather than picks another password. */
    @Test
    void reportsATakenEmailBeforeABreachedPassword() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        String breached = "breached " + UUID.randomUUID();
        hibp.breach(breached);

        assertThat(students.signUp("Bia", email, breached)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/email-taken");
    }

    @Test
    void refusesASignUpWithAnAdminsEmailAsAnyOtherTakenEmail() {
        String email = "admin-" + newEmail();
        accounts.createAdmin(email, "Ana", PASSWORD);

        assertThat(students.signUp("Bia", email, PASSWORD)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/email-taken");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSignUps")
    void refusesAnInvalidSignUpWithTheFieldsCodeAndCreatesNothing(String description, String name, String email,
                                                                  String password, String field, String code) {
        assertThat(students.signUp(name, email, password)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "instance": "/v1/accounts",
                          "errors": [{"field": "%s", "code": "%s"}]
                        }""".formatted(field, code));
        assertThat(students.lookUp(email)).bodyJson().extractingPath("$.exists").isEqualTo(false);
    }

    static Stream<Arguments> invalidSignUps() {
        return Stream.of(
                Arguments.of("password of 7 characters", "Bia", newEmail(), "1234567", "password", "too-short"),
                Arguments.of("password of 73 UTF-8 bytes in 37 characters", "Bia", newEmail(),
                        "\u00e9".repeat(36) + "a", "password", "too-long"),
                Arguments.of("blank name", " \t ", newEmail(), PASSWORD, "name", "required"),
                Arguments.of("name of 81 characters", "b".repeat(81), newEmail(), PASSWORD, "name", "too-long"));
    }

    @Test
    void refusesASignUpWithAnInvalidEmail() {
        assertThat(students.signUp("Bia", "bia@aulaflix", PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "email", "code": "invalid-email"}]
                        }""");
    }

    @Test
    void reportsEveryMissingFieldOfASignUpAtOnce() {
        MvcTestResult result = new BffApi(mvc).post("/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [
                            {"field": "email", "code": "required"},
                            {"field": "name", "code": "required"},
                            {"field": "password", "code": "required"}
                          ]
                        }""");
    }

    @Test
    void signsUpWithAPasswordOfExactlySeventyTwoBytes() {
        String email = newEmail();
        String longestPassword = "\u00e9".repeat(36);

        assertThat(students.signUp("Bia", email, longestPassword)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesABreachedPasswordAtSignUpAndCreatesNothing() {
        String email = newEmail();
        String breached = "breached " + UUID.randomUUID();
        hibp.breach(breached);

        assertThat(students.signUp("Bia", email, breached)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "One or more fields are invalid.",
                          "instance": "/v1/accounts",
                          "timestamp": "%s",
                          "errors": [{"field": "password", "code": "breached"}]
                        }""".formatted(clock.instant()));
        assertThat(students.lookUp(email)).bodyJson().extractingPath("$.exists").isEqualTo(false);
    }

    @Test
    void renamesTheAccountWithTheNameNormalized() {
        String email = newEmail();
        String token = students.signedUp(email, PASSWORD);

        assertThat(students.rename(token, " Beatriz \u00a0 Souza  ")).hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"name": "Beatriz Souza", "email": "%s", "emailConfirmed": false}""".formatted(email));
        assertThat(students.account(token)).bodyJson().extractingPath("$.name").isEqualTo("Beatriz Souza");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNames")
    void refusesAnInvalidNameAndKeepsTheOldOne(String description, String name, String code) {
        String token = students.signedUp(newEmail(), PASSWORD);

        assertThat(students.rename(token, name)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "One or more fields are invalid.",
                          "instance": "/v1/account",
                          "timestamp": "%s",
                          "errors": [{"field": "name", "code": "%s"}]
                        }""".formatted(clock.instant(), code));
        assertThat(students.account(token)).bodyJson().extractingPath("$.name").isEqualTo("Bia");
    }

    static Stream<Arguments> invalidNames() {
        return Stream.of(
                Arguments.of("blank name", " \t ", "required"),
                Arguments.of("name of 81 code points", "\uD83D\uDE00".repeat(81), "too-long"));
    }

    @Test
    void refusesARenameWithoutAName() {
        String token = students.signedUp(newEmail(), PASSWORD);

        MvcTestResult result = new BffApi(mvc).put("/v1/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {"errors": [{"field": "name", "code": "required"}]}""");
    }

    @Test
    void refusesAnAdminsSession() {
        String admin = "admin-" + newEmail();
        accounts.createAdmin(admin, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(admin, PASSWORD);

        for (MvcTestResult result : new MvcTestResult[] {students.account(adminToken),
                students.rename(adminToken, "Outra Ana")}) {
            assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
                    .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .bodyJson().isStrictlyEqualTo("""
                            {
                              "type": "https://aulaflix.com.br/problems/forbidden",
                              "title": "Forbidden",
                              "status": 403,
                              "detail": "This session's role may not do this.",
                              "instance": "/v1/account",
                              "timestamp": "%s"
                            }""".formatted(clock.instant()));
        }
    }

    @Test
    void refusesARequestWithoutASession() {
        BffApi bff = new BffApi(mvc);

        assertThat(bff.get("/v1/account")).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/unauthenticated");
        assertThat(bff.put("/v1/account").contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Bia\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }
}
