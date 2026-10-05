package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.domain.entity.VerificationCodeKind;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.service.VerificationCodes.Issued;
import com.devlabs.aulaflix.service.VerificationCodes.Withheld;

/**
 * The transactional steps of the password flows by code, on a bean of their own so that the checks, HIBP, bcrypt and
 * the wait around them hold no pooled connection. Each takes the Account's row lock first, so that one Account's codes
 * are issued and tried one at a time, and none slips past the limits or the 5 tries a code allows. A code that fails
 * still commits the try it spent.
 */
@Component
class PasswordCodes {

    private static final Logger log = LoggerFactory.getLogger(PasswordCodes.class);

    private final AccountRepository accounts;
    private final VerificationCodes codes;
    private final SessionService sessions;
    private final EmailTemplates templates;
    private final EmailOutbox outbox;
    private final Clock clock;

    PasswordCodes(AccountRepository accounts, VerificationCodes codes, SessionService sessions,
                  EmailTemplates templates, EmailOutbox outbox, Clock clock) {
        this.accounts = accounts;
        this.codes = codes;
        this.sessions = sessions;
        this.templates = templates;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Queues a reset code to a Student's email, and nothing to any other email, nor to a Student whose code the limits
     * withhold: the caller answers all of them alike.
     */
    @Transactional
    public void sendResetCode(String email) {
        accounts.findLockedByEmailAndRole(email, Role.STUDENT).ifPresentOrElse(
                this::sendResetCode,
                () -> log.info("Sent no reset code: the email has no Student Account"));
    }

    /**
     * Sets the Student's new password with a reset code, ends every session of the Account, the squatter's included,
     * and opens a new one; or answers nothing for a code that is not the one, and for an email without a Student
     * Account alike.
     */
    @Transactional
    public Optional<IssuedSession> reset(String email, String code, String passwordHash) {
        Optional<AccountEntity> student = accounts.findLockedByEmailAndRole(email, Role.STUDENT)
                .filter(found -> codes.redeem(found, VerificationCodeKind.RESET, code));
        return student.map(found -> {
            setPassword(found, passwordHash);
            int ended = sessions.endAll(found);
            IssuedSession session = sessions.open(found);
            log.info("Account {} reset its password, ending its {} sessions", found.getId(), ended);
            return session;
        });
    }

    private void sendResetCode(AccountEntity student) {
        switch (codes.issue(student, VerificationCodeKind.RESET)) {
            case Issued issued -> outbox.enqueue(templates.verificationCode(student.getEmail(), student.getName(),
                    VerificationCodeKind.RESET, issued.code()));
            case Withheld withheld -> log.info("Sent Account {} no reset code: limit on {} until {}",
                    student.getId(), withheld.limit(), withheld.until());
        }
    }

    /** A code proves the email the owner's, so it confirms it too, and the owner is told of the new password. */
    private void setPassword(AccountEntity student, String passwordHash) {
        student.setPasswordHash(passwordHash);
        student.confirmEmail(clock.instant());
        outbox.enqueue(templates.passwordChanged(student.getEmail(), student.getName()));
    }
}
