package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Failed sign-ins per normalized email, whether or not an Account has it: 10 within 15 minutes block that email for
 * 15 minutes. The counts live in memory, so a restart forgets them. Any string the public sign-in is sent adds an
 * email, so each one is forgotten once its failures and its block are over, 15 minutes after its last failure, and at
 * most {@value #MAX_EMAILS} are kept: a flood of emails past that makes some forget their failures sooner.
 */
final class SignInFailures {

    private static final int FAILURES_TO_BLOCK = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration BLOCK = Duration.ofMinutes(15);
    private static final long MAX_EMAILS = 100_000;

    private final Clock clock;
    private final Cache<String, Failures> byEmail;

    SignInFailures(Clock clock) {
        this.clock = clock;
        this.byEmail = Caffeine.newBuilder()
                .ticker(() -> nanos(clock.instant()))
                .expireAfterWrite(Collections.max(List.of(WINDOW, BLOCK)))
                .maximumSize(MAX_EMAILS)
                .build();
    }

    /** How long the email stays blocked, or nothing while it may try a password. */
    Optional<Duration> remainingBlock(String email) {
        Instant now = clock.instant();
        return Optional.ofNullable(byEmail.getIfPresent(email))
                .map(Failures::blockedUntil)
                .filter(now::isBefore)
                .map(blockedUntil -> Duration.between(now, blockedUntil));
    }

    void recordFailure(String email) {
        Instant now = clock.instant();
        byEmail.asMap().compute(email, (key, before) -> (before == null ? Failures.NONE : before).add(now));
    }

    /** How many emails it holds failures for, once those it should forget are gone. */
    long emailsKept() {
        byEmail.cleanUp();
        return byEmail.estimatedSize();
    }

    /** Caffeine's time, read from the application's clock so that tests can move it. */
    private static long nanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }

    /** The failures still inside the window, and the end of the last block ({@link Instant#MIN} for none). */
    private record Failures(List<Instant> recent, Instant blockedUntil) {

        static final Failures NONE = new Failures(List.of(), Instant.MIN);

        Failures add(Instant failure) {
            List<Instant> stillRecent = Stream.concat(
                            recent.stream().filter(earlier -> failure.isBefore(earlier.plus(WINDOW))),
                            Stream.of(failure))
                    .toList();
            return stillRecent.size() < FAILURES_TO_BLOCK
                    ? new Failures(stillRecent, blockedUntil)
                    : new Failures(List.of(), failure.plus(BLOCK));
        }
    }
}
