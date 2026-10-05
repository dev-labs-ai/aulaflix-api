package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.EmailedCodes;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.EmailOutbox;

/**
 * Logs must not become a leak. This asserts only what never appears, whatever the wording of the lines: the email, the
 * codes, the new passwords and the session tokens that went through a change, refusals and limits included. The one
 * thing it reads is how many other sessions the change ended, which only the log tells.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordChangeLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "a brand new passphrase";
    private static final String REFUSED_PASSWORD = "short";
    private static final String CODE_SUBJECT = "Seu código para trocar a senha da AulaFlix";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void logsNoEmailCodePasswordOrToken(CapturedOutput output) {
        new StoredOutboxEmails(jdbc).discardPending();
        String email = newEmail();
        StudentApi students = new StudentApi(mvc);
        String session = students.signedUp(email, PASSWORD);
        String otherSession = new StudentApi(mvc).sessionToken(email, PASSWORD);

        assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(students.requestChangeCode(session)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        outbox.drain();
        String code = EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT).getFirst();
        String wrong = "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
        students.changePassword(session, code, REFUSED_PASSWORD);
        students.changePassword(session, wrong, NEW_PASSWORD);
        assertThat(students.changePassword(session, code, NEW_PASSWORD)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();

        assertThat(output.getAll()).isNotBlank()
                .contains("changed its password, ending its 1 other sessions")
                .doesNotContainIgnoringCase(email)
                .doesNotContain(List.of(code, wrong, NEW_PASSWORD, REFUSED_PASSWORD, session, otherSession));
    }
}
