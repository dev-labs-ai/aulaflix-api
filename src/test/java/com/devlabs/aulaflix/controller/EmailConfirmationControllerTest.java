package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.ConfirmationLinks;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;

class EmailConfirmationControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private AccountService accounts;

    private StudentApi students;

    @BeforeEach
    void useTheStudentApi() {
        students = new StudentApi(mvc);
    }

    @Test
    void sendsTheConfirmationLinkAsTheWelcomeEmailOnTheDrainAfterSignUp() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        assertThat(mailpit.to(email)).isEmpty();

        outbox.drain();

        assertThat(mailpit.to(email)).singleElement().satisfies(sent -> {
            assertThat(sent.from()).isEqualTo("AulaFlix <contato@aulaflix.com.br>");
            assertThat(sent.to()).containsExactly(email);
            assertThat(sent.subject()).isEqualTo("Boas-vindas à AulaFlix: confirme seu email");
            assertThat(sent.text()).startsWith("Olá, Bia!").containsPattern(ConfirmationLinks.LINK);
        });
    }

    @Test
    void confirmsTheEmailWithoutASessionAndSignsNoOneIn() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        outbox.drain();

        MvcTestResult result = students.confirmEmail(linkSentTo(email));

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(students.account(session)).hasStatusOk()
                .bodyJson().extractingPath("$.emailConfirmed").isEqualTo(true);
    }

    @Test
    void saysConfirmedAgainOnASecondClick() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        outbox.drain();
        String token = linkSentTo(email);
        assertThat(students.confirmEmail(token)).hasStatus(HttpStatus.NO_CONTENT);

        clock.set(clock.instant().plus(Duration.ofHours(72)).minusSeconds(1));
        assertThat(new StudentApi(mvc).confirmEmail(token)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(students.account(session)).hasStatusOk()
                .bodyJson().extractingPath("$.emailConfirmed").isEqualTo(true);
    }

    @Test
    void refusesALinkPastSeventyTwoHours() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        outbox.drain();

        clock.set(clock.instant().plus(Duration.ofHours(72)));

        assertInvalidLink(students.confirmEmail(linkSentTo(email)));
        assertThat(students.account(session)).hasStatusOk()
                .bodyJson().extractingPath("$.emailConfirmed").isEqualTo(false);
    }

    @Test
    void refusesAUsedLinkOnceItExpires() {
        String email = newEmail();
        students.signedUp(email, PASSWORD);
        outbox.drain();
        String token = linkSentTo(email);
        assertThat(students.confirmEmail(token)).hasStatus(HttpStatus.NO_CONTENT);

        clock.set(clock.instant().plus(Duration.ofHours(72)));

        assertInvalidLink(students.confirmEmail(token));
    }

    @Test
    void refusesAnUnknownLink() {
        assertInvalidLink(students.confirmEmail("q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s"));
    }

    @Test
    void refusesAConfirmationWithoutAToken() {
        MvcTestResult result = new BffApi(mvc).post("/v1/email-confirmations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "token", "code": "required"}]
                        }""");
    }

    @Test
    void refusesThe31stConfirmationFromOneIpWithinAnHour() {
        Instant start = clock.instant();
        StudentApi visitor = new StudentApi(mvc);
        for (int confirmation = 0; confirmation < 30; confirmation++) {
            assertThat(visitor.confirmEmail("unknown-" + confirmation)).hasStatus(HttpStatus.BAD_REQUEST);
        }

        clock.set(start.plus(Duration.ofHours(1)).minusSeconds(1));
        assertRateLimited(visitor.confirmEmail("unknown"), "1");
        clock.set(start.plus(Duration.ofHours(1)));
        assertThat(visitor.confirmEmail("unknown")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void sendsANewLinkAndVoidsThePreviousOne() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        outbox.drain();
        String first = linkSentTo(email);
        clock.set(clock.instant().plusSeconds(60));

        assertThat(students.resendConfirmation(session)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();

        List<String> links = ConfirmationLinks.tokensSentTo(mailpit, email);
        assertThat(links).hasSize(2).doesNotHaveDuplicates();
        assertInvalidLink(students.confirmEmail(first));
        assertThat(students.confirmEmail(links.getFirst())).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(students.account(session)).hasStatusOk()
                .bodyJson().extractingPath("$.emailConfirmed").isEqualTo(true);
    }

    @Test
    void refusesAResendWithinSixtySecondsOfTheLatestLink() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        Instant signedUp = clock.instant();

        clock.set(signedUp.plusSeconds(45));
        assertRateLimited(students.resendConfirmation(session), "15");
        clock.set(signedUp.plusSeconds(60));
        assertThat(students.resendConfirmation(session)).hasStatus(HttpStatus.NO_CONTENT);
        clock.set(signedUp.plusSeconds(119));
        assertRateLimited(students.resendConfirmation(session), "1");

        outbox.drain();
        assertThat(mailpit.to(email)).hasSize(2);
    }

    @Test
    void refusesTheSixthResendWithinADay() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        Instant start = clock.instant();
        for (int resend = 1; resend <= 5; resend++) {
            clock.set(start.plus(Duration.ofMinutes(resend)));
            assertThat(students.resendConfirmation(session)).hasStatus(HttpStatus.NO_CONTENT);
        }

        clock.set(start.plus(Duration.ofHours(23)));
        assertRateLimited(students.resendConfirmation(session), "3660");
        clock.set(start.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(1)));
        assertThat(students.resendConfirmation(session)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesAResendOnceTheEmailIsConfirmed() {
        String email = newEmail();
        String session = students.signedUp(email, PASSWORD);
        outbox.drain();
        assertThat(students.confirmEmail(linkSentTo(email))).hasStatus(HttpStatus.NO_CONTENT);
        clock.set(clock.instant().plusSeconds(60));

        assertThat(students.resendConfirmation(session)).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/email-already-confirmed",
                          "title": "Email already confirmed",
                          "status": 409,
                          "detail": "The Account's email is already confirmed: there is no link to send.",
                          "instance": "/v1/account/confirmation-emails",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
        outbox.drain();
        assertThat(mailpit.to(email)).hasSize(1);
    }

    @Test
    void refusesAResendWithoutASession() {
        assertThat(new BffApi(mvc).post("/v1/account/confirmation-emails").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void refusesAnAdminsSession() {
        String email = "admin-" + newEmail();
        accounts.createAdmin(email, "Ana", PASSWORD);

        assertThat(students.resendConfirmation(new AdminApi(mvc).sessionToken(email, PASSWORD)))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/forbidden");
    }

    private static void assertRateLimited(MvcTestResult result, String retryAfter) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, retryAfter)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/rate-limited");
    }

    /** The token of the only link the address received, failing the test unless there is exactly one. */
    private String linkSentTo(String email) {
        assertThat(mailpit.to(email)).hasSize(1);
        return ConfirmationLinks.tokensSentTo(mailpit, email).getFirst();
    }

    private void assertInvalidLink(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-confirmation-link",
                          "title": "Invalid confirmation link",
                          "status": 400,
                          "detail": "The link is unknown, expired, or replaced by a newer one: ask for a new one.",
                          "instance": "/v1/email-confirmations",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }
}
