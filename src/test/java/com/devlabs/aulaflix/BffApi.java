package com.devlabs.aulaflix;

import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/** Calls the API the way the BFF does: with its key and the browser's IP, and without a session for the catalog. */
public final class BffApi {

    /** The key the tests' application expects; {@link IntegrationTest} sets it. */
    public static final String KEY = "bff-key-of-the-tests-0123456789abcdefghij";
    public static final String CLIENT_IP = "203.0.113.7";

    private final MockMvcTester mvc;

    public BffApi(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    public MockMvcRequestBuilder get(String uri) {
        return mvc.get().uri(uri)
                .header("AulaFlix-BFF-Key", KEY)
                .header("AulaFlix-Client-IP", CLIENT_IP);
    }
}
