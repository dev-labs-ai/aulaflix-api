package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
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
import com.jayway.jsonpath.JsonPath;

/**
 * Logs must not become a leak. This asserts only what never appears, whatever the wording of the lines: the Admin's
 * email, name and token, through every Course mutation and its refusals.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminCourseLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Test
    void logsNoPersonalDataOfTheAdminNorTheirToken(CapturedOutput output) {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        String name = "Ana " + UUID.randomUUID();
        accounts.createAdmin(email, name, PASSWORD);
        String bearer = "Bearer " + new AdminApi(mvc).sessionToken(email, PASSWORD);
        String slug = "curso-" + UUID.randomUUID();

        String created = body(mvc.post().uri("/v1/admin/courses").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\": \"%s\", \"title\": \"Backend\"}".formatted(slug)).exchange());
        String path = "/v1/admin/courses/" + JsonPath.read(created, "$.id");
        mvc.post().uri("/v1/admin/courses").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\": \"%s\", \"title\": \"Backend\"}".formatted(slug)).exchange();
        mvc.put().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\": \"%s\", \"title\": \"Backend com Node.js\"}".formatted(slug)).exchange();
        mvc.put().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"slug": "%s", "title": "Backend", "priceCents": 10, "maxInstallments": 3}"""
                        .formatted(slug)).exchange();
        mvc.delete().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange();
        mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange();

        assertThat(output.getAll()).isNotBlank()
                .doesNotContainIgnoringCase(email)
                .doesNotContain(name, bearer.substring("Bearer ".length()));
    }
}
