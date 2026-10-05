package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.domain.entity.SessionEntity;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.repository.SessionRepository;

/** Opaque, revocable sessions (ADR 0004): a random token, of which only the hash is stored. */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final Duration USE_RECORDING_INTERVAL = Duration.ofMinutes(1);

    private final SessionRepository repository;
    private final Clock clock;

    public SessionService(SessionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public IssuedSession open(AccountEntity account) {
        Instant now = clock.instant();
        String token = SecretTokens.newToken();
        SessionEntity session = repository.save(new SessionEntity(
                SecretTokens.hash(token), account, now, now.plus(lifetimeOf(account.getRole()).max())));
        return new IssuedSession(token, session.getExpiresAt());
    }

    /** The Account behind a token, or nothing for a token that is unknown, expired or revoked; using it counts. */
    @Transactional
    public Optional<AuthenticatedAccount> resolve(String token) {
        Instant now = clock.instant();
        return repository.findByTokenHash(SecretTokens.hash(token))
                .filter(session -> isAlive(session, now))
                .map(session -> {
                    recordUse(session, now);
                    return authenticated(session);
                });
    }

    /** Ends the session the request came with; the Account's other sessions go on. */
    @Transactional
    public void signOut(AuthenticatedAccount account) {
        repository.deleteById(account.sessionId());
        log.info("Account {} signed out of session {}", account.accountId(), account.sessionId());
    }

    /** Ends every session of the Account, and answers how many there were. */
    @Transactional
    public int endAll(AccountEntity account) {
        return repository.deleteByAccountId(account.getId());
    }

    private static boolean isAlive(SessionEntity session, Instant now) {
        Instant idleEnd = session.getLastUsedAt().plus(lifetimeOf(session.getAccount().getRole()).idle());
        return now.isBefore(idleEnd) && now.isBefore(session.getExpiresAt());
    }

    /** At most once a minute, so that a burst of requests writes the row once. */
    private static void recordUse(SessionEntity session, Instant now) {
        if (!now.isBefore(session.getLastUsedAt().plus(USE_RECORDING_INTERVAL))) {
            session.setLastUsedAt(now);
        }
    }

    private static AuthenticatedAccount authenticated(SessionEntity session) {
        AccountEntity account = session.getAccount();
        return new AuthenticatedAccount(account.getId(), account.getRole().name(), session.getId());
    }

    private static Lifetime lifetimeOf(Role role) {
        return switch (role) {
            case ADMIN -> new Lifetime(Duration.ofMinutes(30), Duration.ofHours(8));
            case STUDENT -> new Lifetime(Duration.ofDays(7), Duration.ofDays(30));
        };
    }

    /** How long a session may go unused, and how long it may last at most. */
    private record Lifetime(Duration idle, Duration max) {
    }
}
