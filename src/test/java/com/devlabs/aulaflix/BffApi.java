package com.devlabs.aulaflix;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/**
 * Calls the API the way the BFF does: with its key and the browser's IP, and without a session for the catalog. Each
 * instance is a browser of its own, at an IP no other test uses, so no test inherits another's rate-limit counters.
 * Past a soft limit, a browser that {@linkplain #solvingCaptchas() solves CAPTCHAs} sends a fresh token with each
 * request, as the BFF does once the page has shown Turnstile.
 */
public final class BffApi {

    /** The key the tests' application expects; {@link IntegrationTest} sets it. */
    public static final String KEY = "bff-key-of-the-tests-0123456789abcdefghij";

    private static final AtomicInteger NETWORKS = new AtomicInteger();

    private static final String CAPTCHA_TOKEN_HEADER = "AulaFlix-Captcha-Token";

    private final MockMvcTester mvc;
    private final String clientIp;
    private final Supplier<Optional<String>> captchaTokens;

    public BffApi(MockMvcTester mvc) {
        this(mvc, newClientIp());
    }

    public BffApi(MockMvcTester mvc, String clientIp) {
        this(mvc, clientIp, Optional::empty);
    }

    private BffApi(MockMvcTester mvc, String clientIp, Supplier<Optional<String>> captchaTokens) {
        this.mvc = mvc;
        this.clientIp = clientIp;
        this.captchaTokens = captchaTokens;
    }

    /** The same browser, sending a fresh token, which {@link Turnstile} accepts, with every request. */
    public BffApi solvingCaptchas() {
        return new BffApi(mvc, clientIp, () -> Optional.of(Turnstile.newToken()));
    }

    /** The same browser, sending this token with every request. */
    public BffApi withCaptchaToken(String token) {
        return new BffApi(mvc, clientIp, () -> Optional.of(token));
    }

    /** An IPv4 address in 10.0.0.0/8 that no other test of this run has used. */
    public static String newClientIp() {
        int network = NETWORKS.incrementAndGet();
        return "10.%d.%d.%d".formatted(network >> 16 & 0xff, network >> 8 & 0xff, network & 0xff);
    }

    /** The first four groups of an IPv6 /64 in 2001:db8::/32 that no other test of this run has used. */
    public static String newIpv6Slash64() {
        int network = NETWORKS.incrementAndGet();
        return "2001:db8:%x:%x".formatted(network >> 16 & 0xffff, network & 0xffff);
    }

    public String clientIp() {
        return clientIp;
    }

    public MockMvcRequestBuilder get(String uri) {
        return fromTheBff(mvc.get().uri(uri));
    }

    public MockMvcRequestBuilder post(String uri) {
        return fromTheBff(mvc.post().uri(uri));
    }

    public MockMvcRequestBuilder put(String uri) {
        return fromTheBff(mvc.put().uri(uri));
    }

    public MockMvcRequestBuilder delete(String uri) {
        return fromTheBff(mvc.delete().uri(uri));
    }

    private MockMvcRequestBuilder fromTheBff(MockMvcRequestBuilder request) {
        request.header("AulaFlix-BFF-Key", KEY)
                .header("AulaFlix-Client-IP", clientIp);
        captchaTokens.get().ifPresent(token -> request.header(CAPTCHA_TOKEN_HEADER, token));
        return request;
    }
}
