package com.devlabs.aulaflix;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * One application context for every integration test, so the suite boots it and PostgreSQL once. springdoc would
 * build each OpenAPI document once and cache it; the tests read it fresh, so they see the customizers as they are.
 * The BFF key is the one {@link BffApi} sends. No job runs on its own: a test runs one when it needs to, and the outbox
 * sends far faster than any test queues, so that one drain sends every email queued so far.
 */
@SpringBootTest(properties = {"springdoc.cache.disabled=true", "aulaflix.bff.key=" + BffApi.KEY,
        "aulaflix.codes.hmac-key=" + IntegrationTest.CODES_HMAC_KEY,
        "aulaflix.password-reset.code-request-time=100ms",
        "aulaflix.scheduling.enabled=false", "aulaflix.outbox.send-rate.emails=1000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    /** The key the tests' application signs its 6-digit codes with. */
    public static final String CODES_HMAC_KEY = "codes-hmac-key-of-the-tests-0123456789abc";

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected MockMvcTester mvc;

    /** Every test in the context shares the clock, so each one starts it at the real time. */
    @BeforeEach
    void startTheClockAtTheRealTime() {
        clock.set(Instant.now());
    }
}
