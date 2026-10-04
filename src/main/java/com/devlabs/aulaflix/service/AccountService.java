package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.dto.AccountSummary;
import com.devlabs.aulaflix.exception.EmailTakenException;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;
import com.devlabs.aulaflix.repository.AccountRepository;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountService(AccountRepository repository, PasswordEncoder passwordEncoder, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public AccountSummary createAdmin(String email, String name, String password) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        String normalizedName = AccountInputRules.normalizeName(name);
        List<FieldViolation> violations = AccountInputRules.violations(normalizedEmail, normalizedName, password);
        if (!violations.isEmpty()) {
            throw new InvalidRequestException(violations);
        }
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

    private static AccountSummary summary(AccountEntity account) {
        return new AccountSummary(account.getId(), account.getEmail(), account.getName());
    }
}
