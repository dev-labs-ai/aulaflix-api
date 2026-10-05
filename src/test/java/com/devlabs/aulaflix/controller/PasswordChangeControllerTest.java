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

class PasswordChangeControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "a brand new passphrase";
    private static final String CODE_SUBJECT = "Seu código para trocar a senha da AulaFlix";
    private static final String RESET_CODE_SUBJECT = "Seu código para redefinir a senha da AulaFlix";
    private static final String CHANGED_SUBJECT = "Sua senha da AulaFlix foi alterada";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private AccountService accounts;

    @Autowired
    private Hibp hibp;

    @Autowired
    private JdbcTemplate jdbc;

    private StudentApi students;

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
    void emailsTheStudentASixDigitCode() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);

        MvcTestResult result = students.requestChangeCode(session);

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        outbox.drain();
        List<Mailpit.Email> codes = mailpit.to(email).stream()
                .filter(sent -> sent.subject().equals(CODE_SUBJECT))
                .toList();
        assertThat(codes).singleElement().satisfies(sent -> {
            assertThat(sent.to()).containsExactly(email);
            assertThat(sent.text()).startsWith("Olá, Bia!").contains("trocar a senha", "15 minutos");
            assertThat(EmailedCodes.codeIn(sent)).matches("[0-9]{6}");
        });
    }

    @Test
    void changesThePasswordKeepingThisSessionAndEndingEveryOther() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String otherSession = new StudentApi(mvc).sessionToken(email, PASSWORD);

        MvcTestResult result = students.changePassword(session, codeSentTo(session, email), NEW_PASSWORD);

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(students.account(session)).hasStatusOk();
        assertThat(students.account(otherSession)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(new StudentApi(mvc).signIn(email, PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/invalid-credentials");
        assertThat(new StudentApi(mvc).signIn(email, NEW_PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void endsNoSessionOfAnotherStudent() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String anotherStudentsSession = new StudentApi(mvc).signedUp(newEmail(), PASSWORD);

        assertThat(students.changePassword(session, codeSentTo(session, email), NEW_PASSWORD))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(students.account(anotherStudentsSession)).hasStatusOk();
    }

    @Test
    void confirmsTheEmailAndSendsThePasswordChangedEmail() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        assertThat(students.account(session)).bodyJson().extractingPath("$.emailConfirmed").isEqualTo(false);

        assertThat(students.changePassword(session, codeSentTo(session, email), NEW_PASSWORD))
                .hasStatus(HttpStatus.NO_CONTENT);

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

    @ParameterizedTest
    @ValueSource(strings = {"short", "ééééééééééééééééééééééééééééééééééééé"})
    void refusesANewPasswordOfTheWrongLengthWithoutSpendingTheCode(String newPassword) {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String code = codeSentTo(session, email);
        String fieldCode = newPassword.length() < 8 ? "too-short" : "too-long";

        for (int refusal = 0; refusal < 5; refusal++) {
            assertThat(students.changePassword(session, code, newPassword)).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().isLenientlyEqualTo("""
                            {
                              "type": "https://aulaflix.com.br/problems/invalid-request",
                              "errors": [{"field": "newPassword", "code": "%s"}]
                            }""".formatted(fieldCode));
        }

        assertThat(students.changePassword(session, code, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void takesANewPasswordOfEightCharactersAndRefusesOneOfSeven() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String code = codeSentTo(session, email);
        String eight = UUID.randomUUID().toString().substring(0, 8);

        assertThat(students.changePassword(session, code, eight.substring(1))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {"errors": [{"field": "newPassword", "code": "too-short"}]}""");
        assertThat(students.changePassword(session, code, eight)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(new StudentApi(mvc).signIn(email, eight)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesABreachedNewPasswordWithoutSpendingTheCode() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String code = codeSentTo(session, email);
        String breached = "breached " + UUID.randomUUID();
        hibp.breach(breached);

        for (int refusal = 0; refusal < 5; refusal++) {
            assertThat(students.changePassword(session, code, breached)).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().isLenientlyEqualTo("""
                            {
                              "type": "https://aulaflix.com.br/problems/invalid-request",
                              "errors": [{"field": "newPassword", "code": "breached"}]
                            }""");
        }

        assertThat(students.changePassword(session, code, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(new StudentApi(mvc).signIn(email, NEW_PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void asksHibpOnlyOnceTheFieldsAreValid() {
        String session = students.signedUp(newEmail(), PASSWORD);
        String breached = "breached " + UUID.randomUUID();
        hibp.breach(breached);

        assertThat(students.changePassword(session, "12345", breached)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {"errors": [{"field": "code", "code": "invalid-format"}]}""");
    }

    @ParameterizedTest
    @CsvSource(value = {"'',required", "12345,invalid-format", "1234567,invalid-format", "12a456,invalid-format",
            "' 123456',invalid-format", "１２３４５６,invalid-format"})
    void refusesACodeThatIsNotSixDigits(String code, String fieldCode) {
        String session = students.signedUp(newEmail(), PASSWORD);

        assertThat(students.changePassword(session, code, NEW_PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "code", "code": "%s"}]
                        }""".formatted(fieldCode));
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() {
        String session = students.signedUp(newEmail(), PASSWORD);

        assertThat(students.changePassword(session, "", "")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "errors": [
                            {"field": "code", "code": "required"},
                            {"field": "newPassword", "code": "required"}
                          ]
                        }""");
        assertThat(new BffApi(mvc).put("/v1/account/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + session)
                .contentType(MediaType.APPLICATION_JSON).content("{}").exchange())
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors.length()").isEqualTo(2);
    }

    @Test
    void refusesAWrongCodeAndChangesNothing() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String otherSession = new StudentApi(mvc).sessionToken(email, PASSWORD);

        assertInvalidCode(students.changePassword(session, wrong(codeSentTo(session, email)), NEW_PASSWORD));

        assertThat(students.account(otherSession)).hasStatusOk();
        assertThat(students.account(session)).bodyJson().extractingPath("$.emailConfirmed").isEqualTo(false);
        assertThat(new StudentApi(mvc).signIn(email, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesACodeOnceItHasChangedThePassword() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String code = codeSentTo(session, email);
        assertThat(students.changePassword(session, code, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);

        assertInvalidCode(students.changePassword(session, code, "yet another passphrase"));
        assertThat(new StudentApi(mvc).signIn(email, NEW_PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void takesTheRightCodeAfterFourWrongTries() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String code = codeSentTo(session, email);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertInvalidCode(students.changePassword(session, wrong(code), NEW_PASSWORD));
        }

        assertThat(students.changePassword(session, code, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void voidsTheCodeOnTheFifthWrongTry() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String code = codeSentTo(session, email);
        for (int attempt = 0; attempt < 5; attempt++) {
            assertInvalidCode(students.changePassword(session, wrong(code), NEW_PASSWORD));
        }

        assertInvalidCode(students.changePassword(session, code, NEW_PASSWORD));
        assertThat(new StudentApi(mvc).signIn(email, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesACodePastFifteenMinutes() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        Instant requested = clock.instant();
        String code = codeSentTo(session, email);

        clock.set(requested.plus(Duration.ofMinutes(15)));

        assertInvalidCode(students.changePassword(session, code, NEW_PASSWORD));
    }

    @Test
    void takesACodeUntilItsFifteenMinutesAreOver() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        Instant requested = clock.instant();
        String code = codeSentTo(session, email);

        clock.set(requested.plus(Duration.ofMinutes(15)).minusMillis(1));

        assertThat(students.changePassword(session, code, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesAnEarlierCodeAfterANewOne() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String first = codeSentTo(session, email);
        clock.set(clock.instant().plusSeconds(60));
        String second = codeSentTo(session, email);
        assertThat(EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT)).hasSize(2);

        assertInvalidCode(students.changePassword(session, first, NEW_PASSWORD));
        assertThat(students.changePassword(session, second, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesAChangeWithoutACodeRequested() {
        String session = students.signedUp(newEmail(), PASSWORD);

        assertInvalidCode(students.changePassword(session, "123456", NEW_PASSWORD));
    }

    @Test
    void refusesAResetCode() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        assertThat(students.requestResetCode(email)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();
        String resetCode = EmailedCodes.codesSentTo(mailpit, email, RESET_CODE_SUBJECT).getFirst();

        assertInvalidCode(students.changePassword(session, resetCode, NEW_PASSWORD));
        assertThat(students.resetPassword(email, resetCode, NEW_PASSWORD)).hasStatusOk();
    }

    @Test
    void refusesAChangeCodeForAReset() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String changeCode = codeSentTo(session, email);

        assertThat(students.resetPassword(email, changeCode, NEW_PASSWORD)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/invalid-code");
        assertThat(students.changePassword(session, changeCode, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesANewCodeWithinSixtySecondsOfTheLatest() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        Instant start = clock.instant();
        assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.NO_CONTENT);

        assertRateLimited(students.requestChangeCode(session), "60");
        clock.set(start.plusSeconds(59));
        assertRateLimited(students.requestChangeCode(session), "1");
        outbox.drain();
        assertThat(EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT)).hasSize(1);

        clock.set(start.plusSeconds(60));
        assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();
        assertThat(EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT)).hasSize(2);
    }

    @Test
    void keepsTheCodeAliveThroughARefusedRequest() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        String code = codeSentTo(session, email);

        assertRateLimited(students.requestChangeCode(session), "60");

        assertThat(students.changePassword(session, code, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void sendsAChangeCodeRightAfterAResetCode() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        assertThat(students.requestResetCode(email)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesThe11thCodeWithinADay() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        Instant start = clock.instant();
        for (int request = 0; request < 10; request++) {
            clock.set(start.plus(Duration.ofMinutes(request)));
            assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.NO_CONTENT);
        }

        clock.set(start.plus(Duration.ofHours(23)));
        assertRateLimited(students.requestChangeCode(session), "3600");
        outbox.drain();
        assertThat(EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT)).hasSize(10);

        clock.set(start.plus(Duration.ofDays(1)));
        assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void countsResetCodesTowardTheDailyCap() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        Instant start = clock.instant();
        for (int request = 0; request < 9; request++) {
            clock.set(start.plus(Duration.ofMinutes(request)));
            assertThat(new StudentApi(mvc).requestResetCode(email)).hasStatus(HttpStatus.NO_CONTENT);
        }
        clock.set(start.plus(Duration.ofMinutes(9)));
        assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.NO_CONTENT);

        clock.set(start.plus(Duration.ofMinutes(10)));
        assertRateLimited(students.requestChangeCode(session), "85800");
    }

    @Test
    void refusesARequestWithoutASession() {
        assertThat(new BffApi(mvc).post("/v1/account/password-change-codes").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertThat(new BffApi(mvc).put("/v1/account/password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\",\"newPassword\":\"a brand new passphrase\"}").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void refusesAnAdminsSession() {
        String admin = "admin-" + newEmail();
        accounts.createAdmin(admin, "Ana", PASSWORD);
        String token = new AdminApi(mvc).sessionToken(admin, PASSWORD);

        assertThat(students.requestChangeCode(token)).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/forbidden");
        assertThat(students.changePassword(token, "123456", NEW_PASSWORD)).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/forbidden");
        outbox.drain();
        assertThat(mailpit.to(admin)).isEmpty();
        assertThat(new AdminApi(mvc).signIn(admin, PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    /** Asks for a change code, and reads it from the newest change email the address received. */
    private String codeSentTo(String session, String email) {
        assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();
        return EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT).getFirst();
    }

    /** Another 6-digit code, never the right one. */
    private static String wrong(String code) {
        return "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
    }

    private void assertRateLimited(MvcTestResult result, String retryAfter) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, retryAfter)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/rate-limited",
                          "status": 429,
                          "instance": "/v1/account/password-change-codes"
                        }""");
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
                          "instance": "/v1/account/password",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }
}
