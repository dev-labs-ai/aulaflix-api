package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.devlabs.aulaflix.MutableClock;

/**
 * The public sign-in counts the failures of any string it is sent, so the counts must not outlive their use, nor
 * grow without bound under a flood of made-up emails.
 */
class SignInFailuresTest {

    private static final Instant START = Instant.parse("2026-10-05T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final SignInFailures failures = new SignInFailures(clock);

    @Test
    void forgetsAnEmailFifteenMinutesAfterItsLastFailure() {
        failures.recordFailure("bia@example.com");
        clock.set(START.plus(Duration.ofMinutes(10)));
        failures.recordFailure("bia@example.com");

        clock.set(START.plus(Duration.ofMinutes(25)).minusNanos(1));
        assertThat(failures.emailsKept()).isOne();
        clock.set(START.plus(Duration.ofMinutes(25)));
        assertThat(failures.emailsKept()).isZero();
    }

    @Test
    void forgetsABlockedEmailOnlyOnceItsBlockIsOver() {
        IntStream.range(0, 10).forEach(failure -> failures.recordFailure("bia@example.com"));

        clock.set(START.plus(Duration.ofMinutes(15)).minusNanos(1));
        assertThat(failures.remainingBlock("bia@example.com")).hasValue(Duration.ofNanos(1));
        clock.set(START.plus(Duration.ofMinutes(15)));
        assertThat(failures.emailsKept()).isZero();
    }

    @Test
    void keepsAtMostAHundredThousandEmails() {
        IntStream.rangeClosed(1, 100_001).forEach(email -> failures.recordFailure("bia" + email + "@example.com"));

        assertThat(failures.emailsKept()).isEqualTo(100_000);
    }
}
