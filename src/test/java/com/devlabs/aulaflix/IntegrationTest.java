package com.devlabs.aulaflix;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** One application context for every integration test, so the suite boots it and PostgreSQL once. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected MutableClock clock;

    /** Every test in the context shares the clock, so each one starts it at the real time. */
    @BeforeEach
    void startTheClockAtTheRealTime() {
        clock.set(Instant.now());
    }
}
