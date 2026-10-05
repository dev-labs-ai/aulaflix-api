package com.devlabs.aulaflix;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/**
 * Calls the API the way the BFF does: with its key and the browser's IP, and without a session for the catalog. Each
 * instance is a browser of its own, at an IP no other test uses, so no test inherits another's rate-limit counters.
 */
public final class BffApi {

    /** The key the tests' application expects; {@link IntegrationTest} sets it. */
    public static final String KEY = "bff-key-of-the-tests-0123456789abcdefghij";

    private static final AtomicInteger NETWORKS = new AtomicInteger();

    private final MockMvcTester mvc;
    private final String clientIp;

    public BffApi(MockMvcTester mvc) {
        this(mvc, newClientIp());
    }

    public BffApi(MockMvcTester mvc, String clientIp) {
        this.mvc = mvc;
        this.clientIp = clientIp;
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
        return mvc.get().uri(uri)
                .header("AulaFlix-BFF-Key", KEY)
                .header("AulaFlix-Client-IP", clientIp);
    }
}
