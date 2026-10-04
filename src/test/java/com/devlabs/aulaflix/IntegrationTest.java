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
 * The BFF key is the one {@link BffApi} sends.
 */
@SpringBootTest(properties = {"springdoc.cache.disabled=true", "aulaflix.bff.key=" + BffApi.KEY})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

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
