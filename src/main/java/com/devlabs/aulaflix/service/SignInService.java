package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.exception.InvalidCredentialsException;
import com.devlabs.aulaflix.exception.SignInBlockedException;
import com.devlabs.aulaflix.repository.AccountRepository;

/**
 * Signing in with an email and a password, for one role at a time, each with its own failure counter: so far only the
 * Admins' sign-in, reached only through the SSH tunnel.
 */
@Service
public class SignInService {

    private static final Logger log = LoggerFactory.getLogger(SignInService.class);

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final SignInFailures adminFailures;

    public SignInService(AccountRepository accounts, PasswordEncoder passwordEncoder, SessionService sessions,
                         Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.adminFailures = new SignInFailures(clock);
    }

    public IssuedSession signInAsAdmin(String email, String password) {
        return signIn(Role.ADMIN, adminFailures, email, password);
    }

    /**
     * Checks the block before the password, so that a blocked email learns nothing about its password. An Account of
     * the other role fails like a wrong password. Not transactional: bcrypt runs between the read and the write, and
     * would otherwise hold a pooled connection.
     */
    private IssuedSession signIn(Role role, SignInFailures failures, String email, String password) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        AccountInputRules.requireValid(AccountInputRules.signInViolations(normalizedEmail, password));
        failures.remainingBlock(normalizedEmail).ifPresent(wait -> {
            throw new SignInBlockedException(wait);
        });
        Optional<AccountEntity> account = accounts.findByEmailAndRole(normalizedEmail, role);
        if (account.isEmpty() || !passwordEncoder.matches(password, account.get().getPasswordHash())) {
            failures.recordFailure(normalizedEmail);
            throw new InvalidCredentialsException();
        }
        IssuedSession session = sessions.open(account.get());
        log.info("Account {} signed in as {}", account.get().getId(), role);
        return session;
    }
}
