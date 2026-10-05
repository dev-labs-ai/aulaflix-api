package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.devlabs.aulaflix.exception.RateLimitedException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.caffeine.Bucket4jCaffeine;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;

/**
 * Limits, counted per operation and key in memory, so a restart forgets them. A key's window opens with its first
 * request, and time is the application's {@link Clock}. A request past a limit says whether it is the first of its
 * window, the one worth logging.
 */
@Service
public class RateLimiter {

    /** Bounds the memory a flood of keys can take; a key forgotten this way starts its window again. */
    private static final long MAX_COUNTERS = 100_000;

    private final Clock clock;
    private final ProxyManager<CounterId> counters;
    private final Cache<CounterId, Refusals> refusals = Caffeine.newBuilder().maximumSize(MAX_COUNTERS).build();

    public RateLimiter(Clock clock) {
        this.clock = clock;
        this.counters = Bucket4jCaffeine.<CounterId>builderFor(Caffeine.newBuilder().maximumSize(MAX_COUNTERS))
                .clientClock(new ClockTimeMeter(clock))
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ZERO))
                .build();
    }

    /** Counts one request of the operation for the key, or refuses it once the window's requests are used up. */
    public void consume(RateLimit limit, RateLimitKey key) {
        count(limit, key).ifPresent(overrun -> {
            throw new RateLimitedException("Hard limit on %s reached by %s".formatted(limit.operation(), key),
                    overrun.untilWindowEnds(), overrun.firstOfItsWindow());
        });
    }

    /**
     * Counts one request of the operation for the key, and tells nothing while the window has requests left; past
     * them, it tells how long until the window ends, and whether no other request went past it in that window.
     */
    Optional<Overrun> count(RateLimit limit, RateLimitKey key) {
        CounterId counter = new CounterId(limit.operation(), key);
        ConsumptionProbe probe = counters.getProxy(counter, () -> configuration(limit)).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Optional.empty();
        }
        Duration wait = Duration.ofNanos(probe.getNanosToWaitForRefill());
        return Optional.of(new Overrun(wait, firstRefusalOfItsWindow(counter, wait)));
    }

    /** Whether no other request was refused in the counter's current window, which ends after the wait. */
    private boolean firstRefusalOfItsWindow(CounterId counter, Duration wait) {
        Instant now = clock.instant();
        return refusals.asMap().compute(counter, (id, earlier) -> earlier != null && now.isBefore(earlier.until())
                ? earlier.repeated()
                : new Refusals(now.plus(wait), true)).first();
    }

    /** The whole allowance comes back at once when the window ends, so a window is a fixed span of time. */
    private static BucketConfiguration configuration(RateLimit limit) {
        return BucketConfiguration.builder()
                .addLimit(bandwidth -> bandwidth.capacity(limit.requests())
                        .refillIntervally(limit.requests(), limit.window()))
                .build();
    }

    /** A request past its limit: how long until the window ends, and whether it is the first past it in the window. */
    record Overrun(Duration untilWindowEnds, boolean firstOfItsWindow) {
    }

    /** What a counter counts: one operation for one key. */
    private record CounterId(String operation, RateLimitKey key) {
    }

    /** The end of the window a counter's refusals fall in, and whether the latest was its first. */
    private record Refusals(Instant until, boolean first) {

        Refusals repeated() {
            return new Refusals(until, false);
        }
    }

    /** Bucket4j's time, read from the application's clock so that tests can move it. */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            Instant now = clock.instant();
            return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
