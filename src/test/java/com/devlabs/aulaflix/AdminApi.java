package com.devlabs.aulaflix;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;

/** Signs Admins in and out through the HTTP contract, as every test that needs an Admin session does. */
public final class AdminApi {

    private final MockMvcTester mvc;

    public AdminApi(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    public MvcTestResult signIn(String email, String password) {
        return mvc.post().uri("/v1/admin/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}""".formatted(email, password))
                .exchange();
    }

    /** The token of a new session, failing the test unless the sign-in succeeds. */
    public String sessionToken(String email, String password) {
        return tokenOf(signIn(email, password));
    }

    public MvcTestResult signOut(String token) {
        return mvc.delete().uri("/v1/admin/sessions/current")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    public static String tokenOf(MvcTestResult signIn) {
        assertThat(signIn).hasStatus(HttpStatus.CREATED);
        return JsonPath.read(body(signIn), "$.token");
    }

    public static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (UnsupportedEncodingException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }
}
