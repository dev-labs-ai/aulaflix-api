package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Hibp;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.AccountService;

/**
 * Logs must not become a leak. This asserts only what never appears, whatever the wording of the lines: the emails,
 * passwords and tokens that went through every Student account flow, refusals, blocks and limits included.
 */
@ExtendWith(OutputCaptureExtension.class)
class StudentAccountLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private Hibp hibp;

    @Test
    void logsNoEmailPasswordOrToken(CapturedOutput output) {
        String email = newEmail();
        String unknownEmail = newEmail();
        String adminEmail = "admin-" + newEmail();
        String breached = "breached " + UUID.randomUUID();
        String unchecked = "unchecked " + UUID.randomUUID();
        String uncheckedEmail = newEmail();
        String unknownToken = "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        hibp.breach(breached);
        hibp.answer(unchecked, serviceUnavailable());
        BffApi bff = new BffApi(mvc);
        StudentApi students = new StudentApi(bff);

        String signedUp = students.signedUp(email.toUpperCase(), PASSWORD);
        students.signUp("Bia", email, PASSWORD);
        students.signUp("Bia", unknownEmail, breached);
        students.signUp("Bia", uncheckedEmail, unchecked);
        students.signUp("Bia", unknownEmail, "short");
        students.lookUp(email);
        students.lookUp(unknownEmail);
        students.lookUp(adminEmail);
        students.lookUp(email + "@");
        String signedIn = students.sessionToken(email, PASSWORD);
        students.account(signedIn);
        students.rename(signedIn, "Beatriz");
        students.signOut(signedIn);
        students.account(unknownToken);
        students.signIn(adminEmail, PASSWORD);
        students.signIn(email, "x".repeat(73));
        for (int failure = 0; failure <= 10; failure++) {
            students.signIn(email, breached);
            students.signIn(unknownEmail, breached);
        }
        bff.post("/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": ".formatted(email)).exchange();
        while (students.lookUp(email).getResponse().getStatus() != HttpStatus.TOO_MANY_REQUESTS.value()) {
            students.signIn(email, PASSWORD);
        }
        students.account(new AdminApi(mvc).sessionToken(adminEmail, PASSWORD));

        assertThat(output.getAll()).isNotBlank()
                .doesNotContainIgnoringCase(email)
                .doesNotContainIgnoringCase(unknownEmail)
                .doesNotContainIgnoringCase(adminEmail)
                .doesNotContainIgnoringCase(uncheckedEmail)
                .doesNotContain(PASSWORD, breached, unchecked, "x".repeat(73))
                .doesNotContain(signedUp, signedIn, unknownToken);
    }
}
