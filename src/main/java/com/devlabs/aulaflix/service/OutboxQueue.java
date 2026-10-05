package com.devlabs.aulaflix.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.OutboxEmailEntity;
import com.devlabs.aulaflix.repository.OutboxEmailRepository;

/**
 * The drainer's steps on the queue, each a short transaction of its own, so that no connection is held while SMTP
 * talks.
 */
@Component
class OutboxQueue {

    /** A failed email waits this long, then twice as long after each further failure, up to {@link #LONGEST_WAIT}. */
    static final Duration FIRST_WAIT = Duration.ofMinutes(1);
    static final Duration LONGEST_WAIT = Duration.ofHours(1);

    private final OutboxEmailRepository repository;

    OutboxQueue(OutboxEmailRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<QueuedEmail> due(Instant now, int max) {
        return repository.findDue(now, Limit.of(max)).stream()
                .map(email -> new QueuedEmail(email.getId(), email.getRecipient(), email.getSubject(),
                        email.getBody(), email.getHeaders()))
                .toList();
    }

    @Transactional
    public void recordSent(long id, Instant at) {
        repository.findById(id).orElseThrow().recordSent(at);
    }

    /** Records the failed attempt, and answers when the email is due again and how many attempts it has had. */
    @Transactional
    public Retry recordFailure(long id, Instant at) {
        OutboxEmailEntity email = repository.findById(id).orElseThrow();
        Instant nextAttemptAt = at.plus(waitAfter(email.getAttempts() + 1));
        email.recordFailure(nextAttemptAt);
        return new Retry(email.getAttempts(), nextAttemptAt);
    }

    static Duration waitAfter(int failedAttempts) {
        Duration wait = FIRST_WAIT;
        for (int attempt = 1; attempt < failedAttempts && wait.compareTo(LONGEST_WAIT) < 0; attempt++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(LONGEST_WAIT) < 0 ? wait : LONGEST_WAIT;
    }

    /** An email due for sending, as it was rendered. */
    record QueuedEmail(long id, String recipient, String subject, String body, Map<String, String> headers) {
    }

    record Retry(int attempts, Instant nextAttemptAt) {
    }
}
