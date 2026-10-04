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
 * The Admins' own sign-in, reached only through the SSH tunnel. It keeps its own failure counter, so failures on the
 * public sign-in can never block an Admin.
 */
@Service
public class AdminSignInService {

    private static final Logger log = LoggerFactory.getLogger(AdminSignInService.class);

    private final AccountRepository accounts;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final SignInFailures failures;

    public AdminSignInService(AccountRepository accounts, PasswordEncoder passwordEncoder, SessionService sessions,
                              Clock clock) {
        this.accounts = accounts;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.failures = new SignInFailures(clock);
    }

    /**
     * Checks the block before the password, so that a blocked email learns nothing about its password. Not
     * transactional: bcrypt runs between the read and the write, and would otherwise hold a pooled connection.
     */
    public IssuedSession signIn(String email, String password) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        AccountInputRules.requireValid(AccountInputRules.signInViolations(normalizedEmail, password));
        failures.remainingBlock(normalizedEmail).ifPresent(wait -> {
            throw new SignInBlockedException(wait);
        });
        Optional<AccountEntity> admin = accounts.findByEmailAndRole(normalizedEmail, Role.ADMIN);
        if (admin.isEmpty() || !passwordEncoder.matches(password, admin.get().getPasswordHash())) {
            failures.recordFailure(normalizedEmail);
            throw new InvalidCredentialsException();
        }
        IssuedSession session = sessions.open(admin.get());
        log.info("Admin {} signed in", admin.get().getId());
        return session;
    }
}
