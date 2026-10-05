package com.devlabs.aulaflix;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.FormParameter;

/**
 * Cloudflare Turnstile's {@code siteverify}, played by WireMock so that no test reaches Cloudflare. It accepts every
 * token, as a CAPTCHA a visitor solved, unless a test says that its own token is rejected, or how verifying it answers.
 */
public final class Turnstile {

    /** How long the tests' application waits for {@code siteverify}, short so that a test can outwait it. */
    public static final Duration TIMEOUT = Duration.ofSeconds(1);

    /** The secret the tests' application holds, as the widget's secret key. */
    public static final String SECRET_KEY = "0x4AAAAAAA-turnstile-secret-of-the-tests";

    private static final String SITEVERIFY = "/siteverify";
    private static final int LOWEST_PRIORITY = 10;

    private final WireMockServer server = new WireMockServer(options().dynamicPort());

    public Turnstile() {
        server.start();
        server.stubFor(post(urlPathEqualTo(SITEVERIFY)).atPriority(LOWEST_PRIORITY)
                .willReturn(okJson("""
                        {"success": true, "challenge_ts": "2026-10-05T12:00:00.000Z", "hostname": "aulaflix.com.br",
                         "error-codes": [], "action": "", "cdata": ""}""")));
    }

    /** A token no other test has used, as the widget hands one to a visitor who solved it. */
    public static String newToken() {
        return "0.solved-" + UUID.randomUUID();
    }

    /** The {@code aulaflix.turnstile.*} properties that point the API at this server. */
    public Map<String, Supplier<Object>> applicationProperties() {
        Map<String, Supplier<Object>> properties = new LinkedHashMap<>();
        properties.put("aulaflix.turnstile.base-url", server::baseUrl);
        properties.put("aulaflix.turnstile.secret-key", () -> SECRET_KEY);
        properties.put("aulaflix.turnstile.timeout", TIMEOUT::toString);
        return properties;
    }

    public void stop() {
        server.stop();
    }

    /** Rejects the token, as {@code siteverify} does an invalid, expired or already spent one. */
    public void reject(String token) {
        answer(token, okJson("""
                {"success": false, "error-codes": ["timeout-or-duplicate"], "messages": []}"""));
    }

    /** Answers the token's verification this way: with a fault, an error status, too late, … */
    public void answer(String token, ResponseDefinitionBuilder answer) {
        server.stubFor(post(urlPathEqualTo(SITEVERIFY)).withFormParam("response", equalTo(token)).willReturn(answer));
    }

    /** The form fields of each verification of the token the API asked for, in the order it asked. */
    public List<Map<String, String>> verificationsOf(String token) {
        return server.findAll(postRequestedFor(urlPathEqualTo(SITEVERIFY)).withFormParam("response", equalTo(token)))
                .stream()
                .map(request -> request.formParameters().values().stream()
                        .collect(Collectors.toMap(FormParameter::key, FormParameter::firstValue)))
                .toList();
    }
}
