package com.devlabs.aulaflix.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.dto.AccountSummary;
import com.devlabs.aulaflix.exception.AdminNotFoundException;
import com.devlabs.aulaflix.exception.EmailTakenException;
import com.devlabs.aulaflix.repository.AccountRepository;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final Clock clock;

    public AccountService(AccountRepository repository, PasswordEncoder passwordEncoder, SessionService sessions,
                          Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public AccountSummary createAdmin(String email, String name, String password) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        String normalizedName = AccountInputRules.normalizeName(name);
        AccountInputRules.requireValid(AccountInputRules.violations(normalizedEmail, normalizedName, password));
        if (repository.existsByEmail(normalizedEmail)) {
            throw new EmailTakenException();
        }
        AccountEntity admin = repository.save(new AccountEntity(
                normalizedEmail,
                normalizedName,
                passwordEncoder.encode(password),
                Role.ADMIN,
                clock.instant()));
        log.info("Created Admin Account {}", admin.getId());
        return summary(admin);
    }

    /** The Admins' only way to a new password, since the emailed codes never serve an Admin. */
    @Transactional
    public AccountSummary changeAdminPassword(String email, String newPassword) {
        AccountEntity admin = repository.findByEmailAndRole(AccountInputRules.normalizeEmail(email), Role.ADMIN)
                .orElseThrow(AdminNotFoundException::new);
        AccountInputRules.requireValid(AccountInputRules.newPasswordViolations(newPassword));
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        int endedSessions = sessions.endAll(admin);
        log.info("Changed the password of Admin Account {} and ended its {} sessions", admin.getId(), endedSessions);
        return summary(admin);
    }

    private static AccountSummary summary(AccountEntity account) {
        return new AccountSummary(account.getId(), account.getEmail(), account.getName());
    }
}
