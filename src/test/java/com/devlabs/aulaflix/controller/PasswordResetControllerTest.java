package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.EmailedCodes;
import com.devlabs.aulaflix.Hibp;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.jayway.jsonpath.JsonPath;

class PasswordResetControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "a brand new passphrase";
    private static final String CODE_SUBJECT = "Seu código para redefinir a senha da AulaFlix";
    private static final String CHANGED_SUBJECT = "Sua senha da AulaFlix foi alterada";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private AccountService accounts;

    @Autowired
    private Hibp hibp;

    private StudentApi students;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * A drain stops at the first email that fails, whoever queued it, so each test starts from an empty queue and its
     * drains send only its own emails.
     */
    @BeforeEach
    void startWithAnEmptyOutboxAndTheStudentApi() {
        new StoredOutboxEmails(jdbc).discardPending();
        students = new StudentApi(mvc);
    }

    @Test
    void emailsAStudentASixDigitCode() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);

        MvcTestResult result = students.requestResetCode("  " + email.toUpperCase() + " ");

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        outbox.drain();
        List<Mailpit.Email> codes = mailpit.to(email).stream()
                .filter(sent -> sent.subject().equals(CODE_SUBJECT))
                .toList();
        assertThat(codes).singleElement().satisfies(sent -> {
            assertThat(sent.from()).isEqualTo("AulaFlix <contato@aulaflix.com.br>");
            assertThat(sent.to()).containsExactly(email);
            assertThat(sent.text()).startsWith("Olá, Bia!").contains("15 minutos");
            assertThat(EmailedCodes.codeIn(sent)).matches("[0-9]{6}");
        });
    }

    @Test
    void answersAlikeAndSendsNothingForAnUnknownOrAnAdminsEmail() {
        String unknown = newEmail();
        String admin = "admin-" + newEmail();
        accounts.createAdmin(admin, "Ana", PASSWORD);
        MvcTestResult forAStudent = students.requestResetCode(studentWithAnAccount());

        for (MvcTestResult result : List.of(students.requestResetCode(unknown), students.requestResetCode(admin))) {
            assertAnsweredLike(forAStudent, result);
        }
        outbox.drain();
        assertThat(mailpit.to(unknown)).isEmpty();
        assertThat(mailpit.to(admin)).isEmpty();
    }

    @Test
    void refusesAnInvalidEmailForACode() {
        assertThat(students.requestResetCode("not-an-email")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "email", "code": "invalid-email"}]
                        }""");
        assertThat(new BffApi(mvc).post("/v1/password-reset-codes").contentType(MediaType.APPLICATION_JSON)
                .content("{}").exchange()).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {"errors": [{"field": "email", "code": "required"}]}""");
    }

    @Test
    void resetsThePasswordWithTheRightCodeAndSignsTheStudentInAfresh() {
        String email = newEmail();
        String earlierSession = students.signedUp(email, PASSWORD);
        String otherSession = new StudentApi(mvc).sessionToken(email, PASSWORD);
        clock.set(Instant.parse("2026-10-05T12:00:00Z"));

        MvcTestResult result = students.resetPassword(email, codeSentTo(email), NEW_PASSWORD);

        assertThat(result).hasStatusOk()
                .bodyJson().extractingPath("$.expiresAt").isEqualTo("2026-11-04T12:00:00Z");
        String session = JsonPath.read(AdminApi.body(result), "$.token");
        assertThat(students.account(session)).hasStatusOk();
        assertThat(students.account(earlierSession)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(students.account(otherSession)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new StudentApi(mvc).signIn(email, PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/invalid-credentials");
        assertThat(new StudentApi(mvc).signIn(email, NEW_PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void confirmsTheEmailAndSendsThePasswordChangedEmail() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);

        String session = JsonPath.read(AdminApi.body(students.resetPassword(email, codeSentTo(email), NEW_PASSWORD)),
                "$.token");

        assertThat(students.account(session)).hasStatusOk()
                .bodyJson().extractingPath("$.emailConfirmed").isEqualTo(true);
        outbox.drain();
        assertThat(mailpit.to(email)).filteredOn(sent -> sent.subject().equals(CHANGED_SUBJECT))
                .singleElement().satisfies(sent -> {
                    assertThat(sent.to()).containsExactly(email);
                    assertThat(sent.text()).startsWith("Olá, Bia!")
                            .contains("http://localhost:3001/redefinir-senha");
                });
    }

    @Test
    void liftsTheBlockOnTheEmailsSignIns() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        StudentApi guesser = new StudentApi(mvc);
        for (int failure = 0; failure < 10; failure++) {
            guesser.signIn(email, "wrong password " + failure);
        }
        assertThat(new StudentApi(mvc).signIn(email, PASSWORD)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/sign-in-blocked");

        assertThat(students.resetPassword(email, codeSentTo(email), NEW_PASSWORD)).hasStatusOk();

        assertThat(new StudentApi(mvc).signIn(email, NEW_PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "ééééééééééééééééééééééééééééééééééééé"})
    void refusesANewPasswordOfTheWrongLengthWithoutSpendingTheCode(String newPassword) {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        String code = codeSentTo(email);
        String fieldCode = newPassword.length() < 8 ? "too-short" : "too-long";

        for (int refusal = 0; refusal < 5; refusal++) {
            assertThat(students.resetPassword(email, code, newPassword)).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().isLenientlyEqualTo("""
                            {
                              "type": "https://aulaflix.com.br/problems/invalid-request",
                              "errors": [{"field": "newPassword", "code": "%s"}]
                            }""".formatted(fieldCode));
        }

        assertThat(students.resetPassword(email, code, NEW_PASSWORD)).hasStatusOk();
    }

    @Test
    void refusesABreachedNewPasswordWithoutSpendingTheCode() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        String code = codeSentTo(email);
        String breached = "breached " + UUID.randomUUID();
        hibp.breach(breached);

        for (int refusal = 0; refusal < 5; refusal++) {
            assertThat(students.resetPassword(email, code, breached)).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().isLenientlyEqualTo("""
                            {
                              "type": "https://aulaflix.com.br/problems/invalid-request",
                              "errors": [{"field": "newPassword", "code": "breached"}]
                            }""");
        }

        assertThat(students.resetPassword(email, code, NEW_PASSWORD)).hasStatusOk();
    }

    @ParameterizedTest
    @CsvSource(value = {"'',required", "12345,invalid-format", "1234567,invalid-format", "12a456,invalid-format",
            "' 123456',invalid-format", "１２３４５６,invalid-format"})
    void refusesACodeThatIsNotSixDigits(String code, String fieldCode) {
        assertThat(students.resetPassword(newEmail(), code, NEW_PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "code", "code": "%s"}]
                        }""".formatted(fieldCode));
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() {
        assertThat(students.resetPassword("", "", "")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "errors": [
                            {"field": "email", "code": "required"},
                            {"field": "code", "code": "required"},
                            {"field": "newPassword", "code": "required"}
                          ]
                        }""");
        assertThat(new BffApi(mvc).post("/v1/password-resets").contentType(MediaType.APPLICATION_JSON)
                .content("{}").exchange()).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.length()").isEqualTo(3);
    }

    @Test
    void refusesACodeOnceItHasReset() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        String code = codeSentTo(email);
        assertThat(students.resetPassword(email, code, NEW_PASSWORD)).hasStatusOk();

        assertInvalidCode(students.resetPassword(email, code, "yet another passphrase"));
        assertThat(new StudentApi(mvc).signIn(email, NEW_PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesAWrongCode() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);

        assertInvalidCode(students.resetPassword(email, wrong(codeSentTo(email)), NEW_PASSWORD));
        assertThat(students.account(session)).hasStatusOk();
        assertThat(new StudentApi(mvc).signIn(email, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void takesTheRightCodeAfterFourWrongTries() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        String code = codeSentTo(email);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertInvalidCode(students.resetPassword(email, wrong(code), NEW_PASSWORD));
        }

        assertThat(students.resetPassword(email, code, NEW_PASSWORD)).hasStatusOk();
    }

    @Test
    void voidsTheCodeOnTheFifthWrongTry() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        String code = codeSentTo(email);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertInvalidCode(students.resetPassword(email, wrong(code), NEW_PASSWORD));
        }

        assertInvalidCode(students.resetPassword(email, code, NEW_PASSWORD));
        assertThat(new StudentApi(mvc).signIn(email, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesACodePastFifteenMinutes() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        Instant requested = clock.instant();
        String code = codeSentTo(email);

        clock.set(requested.plus(Duration.ofMinutes(15)));

        assertInvalidCode(students.resetPassword(email, code, NEW_PASSWORD));
    }

    @Test
    void takesACodeUntilItsFifteenMinutesAreOver() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        Instant requested = clock.instant();
        String code = codeSentTo(email);

        clock.set(requested.plus(Duration.ofMinutes(15)).minusMillis(1));

        assertThat(students.resetPassword(email, code, NEW_PASSWORD)).hasStatusOk();
    }

    @Test
    void refusesAnEarlierCodeAfterANewOne() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        String first = codeSentTo(email);
        clock.set(clock.instant().plusSeconds(60));
        assertThat(students.requestResetCode(email)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();
        List<String> codes = EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT);
        assertThat(codes).hasSize(2);

        assertInvalidCode(students.resetPassword(email, first, NEW_PASSWORD));
        assertThat(students.resetPassword(email, codes.getFirst(), NEW_PASSWORD)).hasStatusOk();
    }

    @Test
    void refusesAResetWithoutACodeRequested() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);

        assertInvalidCode(students.resetPassword(email, "123456", NEW_PASSWORD));
    }

    @Test
    void refusesAResetOfAnUnknownOrAnAdminsEmailAlike() {
        String admin = "admin-" + newEmail();
        accounts.createAdmin(admin, "Ana", PASSWORD);
        assertThat(students.requestResetCode(admin)).hasStatus(HttpStatus.NO_CONTENT);

        assertInvalidCode(students.resetPassword(newEmail(), "123456", NEW_PASSWORD));
        assertInvalidCode(students.resetPassword(admin, "123456", NEW_PASSWORD));
        assertThat(new AdminApi(mvc).signIn(admin, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void sendsNoNewCodeWithinSixtySecondsOfTheLatest() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        Instant start = clock.instant();
        MvcTestResult first = students.requestResetCode(email);

        clock.set(start.plusSeconds(59));
        assertAnsweredLike(first, students.requestResetCode(email));
        outbox.drain();
        assertThat(EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT)).hasSize(1);

        clock.set(start.plusSeconds(60));
        assertThat(students.requestResetCode(email)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();
        assertThat(EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT)).hasSize(2);
    }

    @Test
    void sendsNoMoreThanTenCodesWithinADay() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        Instant start = clock.instant();
        MvcTestResult first = null;
        for (int request = 0; request < 10; request++) {
            clock.set(start.plus(Duration.ofMinutes(request)));
            MvcTestResult result = new StudentApi(mvc).requestResetCode(email);
            first = first == null ? result : first;
        }

        clock.set(start.plus(Duration.ofHours(23)));
        assertAnsweredLike(first, new StudentApi(mvc).requestResetCode(email));
        outbox.drain();
        assertThat(EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT)).hasSize(10);

        clock.set(start.plus(Duration.ofDays(1)));
        assertThat(new StudentApi(mvc).requestResetCode(email)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();
        assertThat(EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT)).hasSize(11);
    }

    @Test
    void refusesThe21stCodeRequestFromOneIpWithinADay() {
        Instant start = clock.instant();
        StudentApi visitor = new StudentApi(mvc);
        for (int request = 0; request < 20; request++) {
            assertThat(visitor.requestResetCode(newEmail())).hasStatus(HttpStatus.NO_CONTENT);
        }

        clock.set(start.plus(Duration.ofDays(1)).minusSeconds(1));
        assertRateLimited(visitor.requestResetCode(newEmail()), "1");
        clock.set(start.plus(Duration.ofDays(1)));
        assertThat(visitor.requestResetCode(newEmail())).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesThe31stResetFromOneIpWithinAnHour() {
        Instant start = clock.instant();
        StudentApi visitor = new StudentApi(mvc);
        for (int reset = 0; reset < 30; reset++) {
            assertThat(visitor.resetPassword(newEmail(), "12345", NEW_PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST);
        }

        clock.set(start.plus(Duration.ofHours(1)).minusSeconds(1));
        assertRateLimited(visitor.resetPassword(newEmail(), "12345", NEW_PASSWORD), "1");
        clock.set(start.plus(Duration.ofHours(1)));
        assertThat(visitor.resetPassword(newEmail(), "12345", NEW_PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private String studentWithAnAccount() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        return email;
    }

    /** Asks for a reset code, and reads it from the newest reset email the address received. */
    private String codeSentTo(String email) {
        assertThat(students.requestResetCode(email)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();
        return EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT).getFirst();
    }

    /** Another 6-digit code, never the right one. */
    private static String wrong(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    /** The same status, headers and empty body: nothing in the answer tells the two requests apart. */
    private static void assertAnsweredLike(MvcTestResult expected, MvcTestResult actual) {
        assertThat(actual).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(actual.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(actual.getResponse().getHeaderNames())
                .containsExactlyInAnyOrderElementsOf(expected.getResponse().getHeaderNames());
        for (String header : expected.getResponse().getHeaderNames()) {
            assertThat(actual.getResponse().getHeaders(header)).as(header)
                    .isEqualTo(expected.getResponse().getHeaders(header));
        }
    }

    private static void assertRateLimited(MvcTestResult result, String retryAfter) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, retryAfter)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/rate-limited");
    }

    private void assertInvalidCode(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-code",
                          "title": "Invalid code",
                          "status": 400,
                          "detail": "The code is wrong or expired: check it, or ask for a new one.",
                          "instance": "/v1/password-resets",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }
}
