package com.devlabs.aulaflix.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.exception.EmailTakenException;
import com.devlabs.aulaflix.repository.AccountRepository;

/**
 * The one transactional step of a sign-up, on a bean of its own so that the checks and bcrypt before it hold no pooled
 * connection: storing the Student's Account, opening its first session and queuing the confirmation link.
 */
@Component
class StudentSignUps {

    private static final Logger log = LoggerFactory.getLogger(StudentSignUps.class);

    private final AccountRepository repository;
    private final SessionService sessions;
    private final EmailConfirmationService confirmations;
    private final Clock clock;

    StudentSignUps(AccountRepository repository, SessionService sessions, EmailConfirmationService confirmations,
                   Clock clock) {
        this.repository = repository;
        this.sessions = sessions;
        this.confirmations = confirmations;
        this.clock = clock;
    }

    /**
     * Sign-ups of one email go one at a time, under a lock on the email, and each checks the email again once it holds
     * the lock: sign-ups sent at once all pass the check made before bcrypt, and the unique email would refuse all but
     * one only after the insert's values were logged.
     */
    @Transactional
    public IssuedSession store(NewAccount student, String passwordHash) {
        repository.lockEmail(student.email());
        if (repository.existsByEmail(student.email())) {
            throw new EmailTakenException();
        }
        AccountEntity stored = repository.save(new AccountEntity(
                student.email(), student.name(), passwordHash, Role.STUDENT, clock.instant()));
        IssuedSession session = sessions.open(stored);
        confirmations.sendFirstLink(stored);
        log.info("Student Account {} signed up", stored.getId());
        return session;
    }
}
