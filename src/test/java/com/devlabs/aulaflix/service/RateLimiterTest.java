package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.devlabs.aulaflix.MutableClock;
import com.devlabs.aulaflix.exception.RateLimitedException;

/**
 * The limiter keeps its counters in Bucket4j's Caffeine store, so these tests are also what shows Bucket4j 8.21.0 runs
 * on the build's JDK with the Caffeine 3 the Boot BOM manages, which it was not built against.
 */
class RateLimiterTest {

    private static final RateLimit THREE_A_MINUTE = new RateLimit("test-operation", 3, Duration.ofMinutes(1));
    private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final RateLimiter limiter = new RateLimiter(clock);

    @Test
    void refusesTheRequestPastTheLimitUntilTheWindowEnds() {
        RateLimitKey ip = RateLimitKey.clientIp(InetAddress.ofLiteral("203.0.113.7"));
        consumeAll(THREE_A_MINUTE, ip);

        assertThat(refusal(THREE_A_MINUTE, ip).retryAfter()).isEqualTo(Duration.ofMinutes(1));
        clock.set(START.plusSeconds(59));
        assertThat(refusal(THREE_A_MINUTE, ip).retryAfter()).isEqualTo(Duration.ofSeconds(1));
        clock.set(START.plusSeconds(60));
        assertThatCode(() -> limiter.consume(THREE_A_MINUTE, ip)).doesNotThrowAnyException();
    }

    @Test
    void countsEachClientIpApart() {
        RateLimitKey ip = RateLimitKey.clientIp(InetAddress.ofLiteral("203.0.113.7"));
        consumeAll(THREE_A_MINUTE, ip);

        RateLimitKey anotherIp = RateLimitKey.clientIp(InetAddress.ofLiteral("203.0.113.8"));
        assertThatCode(() -> limiter.consume(THREE_A_MINUTE, anotherIp)).doesNotThrowAnyException();
    }

    @Test
    void countsEachStudentApartFromOtherStudentsAndFromTheirIp() {
        consumeAll(THREE_A_MINUTE, RateLimitKey.student(42));

        assertThat(refusal(THREE_A_MINUTE, RateLimitKey.student(42)).retryAfter()).isEqualTo(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.consume(THREE_A_MINUTE, RateLimitKey.student(43))).doesNotThrowAnyException();
        assertThatCode(() -> limiter.consume(THREE_A_MINUTE, RateLimitKey.clientIp(InetAddress.ofLiteral("0.0.0.42"))))
                .doesNotThrowAnyException();
    }

    @Test
    void countsEveryoneTogetherUnderAGlobalLimit() {
        consumeAll(THREE_A_MINUTE, RateLimitKey.everyone());

        assertThat(refusal(THREE_A_MINUTE, RateLimitKey.everyone()).retryAfter()).isEqualTo(Duration.ofMinutes(1));
        assertThatCode(() -> limiter.consume(THREE_A_MINUTE, RateLimitKey.student(1))).doesNotThrowAnyException();
    }

    @Test
    void countsEachOperationApart() {
        RateLimitKey ip = RateLimitKey.clientIp(InetAddress.ofLiteral("203.0.113.7"));
        consumeAll(THREE_A_MINUTE, ip);

        RateLimit another = new RateLimit("another-operation", 3, Duration.ofMinutes(1));
        consumeAll(another, ip);
        assertThat(refusal(another, ip).retryAfter()).isEqualTo(Duration.ofMinutes(1));
    }

    /** Only the first refusal of a window is logged, so the refusal says whether it is that one. */
    @Test
    void marksTheFirstRefusalOfEachWindowAndNoOther() {
        RateLimitKey ip = RateLimitKey.clientIp(InetAddress.ofLiteral("203.0.113.7"));
        consumeAll(THREE_A_MINUTE, ip);

        assertThat(refusal(THREE_A_MINUTE, ip).firstOfItsWindow()).isTrue();
        assertThat(refusal(THREE_A_MINUTE, ip).firstOfItsWindow()).isFalse();
        clock.set(START.plusSeconds(59));
        assertThat(refusal(THREE_A_MINUTE, ip).firstOfItsWindow()).isFalse();
        clock.set(START.plusSeconds(60));
        consumeAll(THREE_A_MINUTE, ip);
        assertThat(refusal(THREE_A_MINUTE, ip).firstOfItsWindow()).isTrue();
        assertThat(refusal(THREE_A_MINUTE, ip).firstOfItsWindow()).isFalse();
    }

    @Test
    void namesTheLimitAndTheKeyItCounts() {
        consumeAll(THREE_A_MINUTE, RateLimitKey.student(42));

        assertThat(refusal(THREE_A_MINUTE, RateLimitKey.student(42)))
                .hasMessage("Hard limit on test-operation reached by Student 42");
    }

    private void consumeAll(RateLimit limit, RateLimitKey key) {
        for (int request = 0; request < limit.requests(); request++) {
            assertThatCode(() -> limiter.consume(limit, key)).doesNotThrowAnyException();
        }
    }

    private RateLimitedException refusal(RateLimit limit, RateLimitKey key) {
        RateLimitedException refusal = catchThrowableOfType(RateLimitedException.class,
                () -> limiter.consume(limit, key));
        assertThat(refusal).as("the request past %s for %s", limit, key).isNotNull();
        return refusal;
    }
}
