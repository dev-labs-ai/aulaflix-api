package com.devlabs.aulaflix;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * Looks Students up, signs them up, in and out, and reads and edits their Account through the HTTP contract, the way
 * the BFF does for one browser: every call comes from that browser's IP. The fields go out as JSON strings, escaped,
 * whatever they hold.
 */
public final class StudentApi {

    private final BffApi bff;

    public StudentApi(MockMvcTester mvc) {
        this(new BffApi(mvc));
    }

    public StudentApi(BffApi bff) {
        this.bff = bff;
    }

    /** An email no other test has used. */
    public static String newEmail() {
        return "student-" + UUID.randomUUID() + "@aulaflix.com.br";
    }

    public MvcTestResult lookUp(String email) {
        return bff.post("/v1/account-lookups")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email)))
                .exchange();
    }

    public MvcTestResult signUp(String name, String email, String password) {
        return bff.post("/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", name, "email", email, "password", password)))
                .exchange();
    }

    /** The token of a new Student's first session, failing the test unless the sign-up succeeds. */
    public String signedUp(String email, String password) {
        return AdminApi.tokenOf(signUp("Bia", email, password));
    }

    public MvcTestResult signIn(String email, String password) {
        return bff.post("/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password)))
                .exchange();
    }

    /** The token of a new session, failing the test unless the sign-in succeeds. */
    public String sessionToken(String email, String password) {
        return AdminApi.tokenOf(signIn(email, password));
    }

    public MvcTestResult signOut(String token) {
        return bff.delete("/v1/sessions/current")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    public MvcTestResult account(String token) {
        return bff.get("/v1/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    public MvcTestResult rename(String token, String name) {
        return bff.put("/v1/account")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", name)))
                .exchange();
    }

    private static String json(Map<String, String> fields) {
        return JsonMapper.shared().writeValueAsString(fields);
    }
}
