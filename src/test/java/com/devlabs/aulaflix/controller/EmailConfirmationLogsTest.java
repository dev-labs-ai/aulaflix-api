package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import com.devlabs.aulaflix.ConfirmationLinks;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.SmtpRelay;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.EmailOutbox;

/**
 * Logs must not become a leak. This asserts only what never appears, whatever the wording of the lines: the email
 * and the tokens that went through the confirmation link and the outbox, failed sends, refusals and limits included.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailConfirmationLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private SmtpRelay smtp;

    @AfterEach
    void bringTheSmtpServerBack() {
        smtp.open();
    }

    @Test
    void logsNoEmailOrToken(CapturedOutput output) {
        outbox.drain();
        String email = newEmail();
        StudentApi students = new StudentApi(mvc);
        smtp.takeDown();
        String session = students.signedUp(email, PASSWORD);
        outbox.drain();
        smtp.open();
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        outbox.drain();
        String first = ConfirmationLinks.tokensSentTo(mailpit, email).getFirst();

        students.resendConfirmation(session);
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        students.resendConfirmation(session);
        outbox.drain();
        List<String> links = ConfirmationLinks.tokensSentTo(mailpit, email);
        students.confirmEmail(first);
        students.confirmEmail(links.getFirst());
        students.confirmEmail(links.getFirst());
        students.resendConfirmation(session);
        while (students.confirmEmail(links.get(1)).getResponse().getStatus()
                != HttpStatus.TOO_MANY_REQUESTS.value()) {
            students.confirmEmail(first);
        }

        assertThat(links).hasSize(3);
        assertThat(output.getAll()).isNotBlank()
                .contains("Sent outbox email")
                .doesNotContainIgnoringCase(email)
                .doesNotContain(links)
                .doesNotContain(session);
    }
}
