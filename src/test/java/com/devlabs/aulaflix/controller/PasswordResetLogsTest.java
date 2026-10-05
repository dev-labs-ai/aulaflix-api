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

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.EmailedCodes;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.jayway.jsonpath.JsonPath;

/**
 * Logs must not become a leak. This asserts only what never appears, whatever the wording of the lines: the emails,
 * the codes, the new passwords and the session tokens that went through a reset, refusals and limits included.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "a brand new passphrase";
    private static final String REFUSED_PASSWORD = "short";
    private static final String CODE_SUBJECT = "Seu código para redefinir a senha da AulaFlix";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private AccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void logsNoEmailCodePasswordOrToken(CapturedOutput output) {
        new StoredOutboxEmails(jdbc).discardPending();
        String email = newEmail();
        String unknown = newEmail();
        String admin = "admin-" + newEmail();
        accounts.createAdmin(admin, "Ana", PASSWORD);
        StudentApi students = new StudentApi(mvc);
        String earlierSession = students.signedUp(email, PASSWORD);

        students.requestResetCode(email);
        students.requestResetCode(email);
        students.requestResetCode(unknown);
        students.requestResetCode(admin);
        outbox.drain();
        String code = EmailedCodes.codesSentTo(mailpit, email, CODE_SUBJECT).getFirst();
        String wrong = "%06d".formatted((Integer.parseInt(code) + 1) % 1_000_000);
        students.resetPassword(email, code, REFUSED_PASSWORD);
        students.resetPassword(email, wrong, NEW_PASSWORD);
        students.resetPassword(unknown, code, NEW_PASSWORD);
        String session = JsonPath.read(AdminApi.body(students.resetPassword(email, code, NEW_PASSWORD)), "$.token");
        StudentApi flooder = new StudentApi(mvc);
        int refused = 0;
        while (flooder.resetPassword(email, wrong, NEW_PASSWORD).getResponse().getStatus()
                != HttpStatus.TOO_MANY_REQUESTS.value()) {
            refused++;
        }
        outbox.drain();

        assertThat(refused).isEqualTo(30);
        assertThat(output.getAll()).isNotBlank()
                .contains("reset its password")
                .doesNotContainIgnoringCase(email)
                .doesNotContainIgnoringCase(unknown)
                .doesNotContainIgnoringCase(admin)
                .doesNotContain(List.of(code, wrong, NEW_PASSWORD, REFUSED_PASSWORD, session, earlierSession));
    }
}
