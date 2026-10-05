package com.devlabs.aulaflix.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * At most {@code emails} attempts within any span of {@code span}, counted over the attempts themselves rather than in
 * fixed windows, so that no burst across a window's edge doubles the rate. In memory: a restart forgets it. Not
 * thread-safe: only the drainer uses it, one drain at a time.
 */
final class SendRate {

    private final int emails;
    private final Duration span;
    private final Deque<Instant> attempts = new ArrayDeque<>();

    SendRate(int emails, Duration span) {
        this.emails = emails;
        this.span = span;
    }

    /** How many attempts may start now. */
    int available(Instant now) {
        forgetOutside(now);
        return emails - attempts.size();
    }

    void record(Instant attempt) {
        attempts.addLast(attempt);
    }

    /**
     * Drops the attempts a span or more ago, and those after now, which only a clock set back can leave: they would
     * otherwise hold the rate down until the clock caught up with them.
     */
    private void forgetOutside(Instant now) {
        Instant spanStart = now.minus(span);
        attempts.removeIf(attempt -> !attempt.isAfter(spanStart) || attempt.isAfter(now));
    }
}
