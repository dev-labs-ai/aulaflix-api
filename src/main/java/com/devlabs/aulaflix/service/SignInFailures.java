package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Failed sign-ins per normalized email, whether or not an Account has it: 10 within 15 minutes block that email for
 * 15 minutes. The counts live in memory, so a restart forgets them.
 */
final class SignInFailures {

    private static final int FAILURES_TO_BLOCK = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration BLOCK = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<String, Failures> byEmail = new ConcurrentHashMap<>();

    SignInFailures(Clock clock) {
        this.clock = clock;
    }

    /** How long the email stays blocked, or nothing while it may try a password. */
    Optional<Duration> remainingBlock(String email) {
        Instant now = clock.instant();
        return Optional.ofNullable(byEmail.get(email))
                .map(Failures::blockedUntil)
                .filter(now::isBefore)
                .map(blockedUntil -> Duration.between(now, blockedUntil));
    }

    void recordFailure(String email) {
        Instant now = clock.instant();
        byEmail.compute(email, (key, before) -> (before == null ? Failures.NONE : before).add(now));
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
