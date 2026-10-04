package com.devlabs.aulaflix.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.service.AccountService;

/**
 * Logs must not become a leak. This asserts only what never appears, whatever the wording of the lines: the
 * credentials and tokens that went through every Admin session flow, refusals and the password change included.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminSessionLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String NEW_PASSWORD = "tr0ub4dor & 3 staples";

    @Autowired
    private AccountService accounts;

    @Test
    void logsNoEmailPasswordOrToken(CapturedOutput output) {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        String unknownEmail = "nobody-" + UUID.randomUUID() + "@aulaflix.com.br";
        String unknownToken = "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s";
        accounts.createAdmin(email, "Ana", PASSWORD);
        AdminApi api = new AdminApi(mvc);

        String signedOut = api.sessionToken(email.toUpperCase(), PASSWORD);
        api.signOut(signedOut);
        String ended = api.sessionToken(email, PASSWORD);
        api.signIn(email, "x".repeat(73));
        for (int failure = 0; failure <= 10; failure++) {
            api.signIn(email, NEW_PASSWORD);
            api.signIn(unknownEmail, NEW_PASSWORD);
        }
        mvc.delete().uri("/v1/admin/sessions/current")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + unknownToken).exchange();
        mvc.post().uri("/v1/admin/sessions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": ".formatted(email)).exchange();
        accounts.changeAdminPassword(email, NEW_PASSWORD);

        assertThat(output.getAll()).isNotBlank()
                .doesNotContainIgnoringCase(email)
                .doesNotContainIgnoringCase(unknownEmail)
                .doesNotContain(PASSWORD, NEW_PASSWORD, "x".repeat(73))
                .doesNotContain(signedOut, ended, unknownToken);
    }
}
