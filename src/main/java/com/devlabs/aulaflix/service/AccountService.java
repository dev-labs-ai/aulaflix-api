package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.dto.Account;
import com.devlabs.aulaflix.dto.AccountSummary;
import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.exception.AdminNotFoundException;
import com.devlabs.aulaflix.exception.EmailTakenException;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;
import com.devlabs.aulaflix.repository.AccountRepository;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final CompromisedPasswordChecker breachedPasswords;
    private final StudentSignUps studentSignUps;
    private final SessionService sessions;
    private final Clock clock;

    public AccountService(AccountRepository repository, PasswordEncoder passwordEncoder,
                          CompromisedPasswordChecker breachedPasswords, StudentSignUps studentSignUps,
                          SessionService sessions, Clock clock) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.breachedPasswords = breachedPasswords;
        this.studentSignUps = studentSignUps;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Whether an Account, a Student's or an Admin's alike, has the email (ADR 0005). */
    @Transactional(readOnly = true)
    public boolean exists(String email) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        AccountInputRules.requireValid(AccountInputRules.emailViolations(normalizedEmail));
        return repository.existsByEmail(normalizedEmail);
    }

    /** Creates a Student's Account and signs the Student in at once; an Admin's email is taken like any other. */
    public IssuedSession signUp(String email, String name, String password) {
        NewAccount student = requireNewAccount(email, name, password);
        return studentSignUps.store(student, passwordEncoder.encode(password));
    }

    /** The Account of a Student's session. */
    @Transactional(readOnly = true)
    public Account account(long accountId) {
        return details(repository.findById(accountId).orElseThrow());
    }

    /** Sets a Student's name, normalized, and answers the Account as it now is. */
    @Transactional
    public Account rename(long accountId, String name) {
        String normalizedName = AccountInputRules.normalizeName(name);
        AccountInputRules.requireValid(AccountInputRules.nameViolations(normalizedName));
        AccountEntity student = repository.findById(accountId).orElseThrow();
        student.setName(normalizedName);
        return details(student);
    }

    @Transactional
    public AccountSummary createAdmin(String email, String name, String password) {
        NewAccount newAdmin = requireNewAccount(email, name, password);
        AccountEntity admin = repository.save(new AccountEntity(
                newAdmin.email(),
                newAdmin.name(),
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
        requireUnbreached(newPassword);
        admin.setPasswordHash(passwordEncoder.encode(newPassword));
        int endedSessions = sessions.endAll(admin);
        log.info("Changed the password of Admin Account {} and ended its {} sessions", admin.getId(), endedSessions);
        return summary(admin);
    }

    /**
     * The checks of a new Account, in the order they are reported: the fields, then the email, then HIBP, which is
     * asked only once the rest of the request is valid, so that a refused request never reaches it.
     */
    private NewAccount requireNewAccount(String email, String name, String password) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        String normalizedName = AccountInputRules.normalizeName(name);
        AccountInputRules.requireValid(AccountInputRules.violations(normalizedEmail, normalizedName, password));
        if (repository.existsByEmail(normalizedEmail)) {
            throw new EmailTakenException();
        }
        requireUnbreached(password);
        return new NewAccount(normalizedEmail, normalizedName);
    }

    private void requireUnbreached(String password) {
        if (breachedPasswords.check(password).isCompromised()) {
            throw new InvalidRequestException(List.of(new FieldViolation("password", "breached")));
        }
    }

    private static AccountSummary summary(AccountEntity account) {
        return new AccountSummary(account.getId(), account.getEmail(), account.getName());
    }

    private static Account details(AccountEntity account) {
        return new Account(account.getName(), account.getEmail(), account.getEmailConfirmedAt() != null);
    }
}
