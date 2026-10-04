package com.devlabs.aulaflix.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.service.AccountService;

/** The refusals Spring MVC makes on its own carry the API's problem types too. */
class FrameworkRefusalsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    private String token;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
    }

    @Test
    void answersAnUnknownPathWithNotFound() {
        assertThat(mvc.get().uri("/v1/admin/nothing-here").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/not-found",
                          "title": "Not found",
                          "status": 404,
                          "instance": "/v1/admin/nothing-here",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }

    @Test
    void answersAnUnsupportedMethodWithMethodNotAllowed() {
        assertThat(mvc.get().uri("/v1/admin/sessions/current").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .hasHeader(HttpHeaders.ALLOW, "DELETE")
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/method-not-allowed",
                          "title": "Method not allowed",
                          "status": 405,
                          "instance": "/v1/admin/sessions/current",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }

    @Test
    void answersABodyThatIsNotJsonWithUnsupportedMediaType() {
        assertThat(mvc.post().uri("/v1/admin/sessions").contentType(MediaType.TEXT_PLAIN).content("admin"))
                .hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/unsupported-media-type",
                          "title": "Unsupported media type",
                          "status": 415,
                          "instance": "/v1/admin/sessions",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }
}
