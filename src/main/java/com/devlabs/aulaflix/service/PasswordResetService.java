package com.devlabs.aulaflix.service;

import java.time.Duration;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.devlabs.aulaflix.dto.IssuedSession;
import com.devlabs.aulaflix.exception.InvalidCodeException;

/**
 * A Student who forgot their password sets a new one with a 6-digit code sent to their email. Asking for a code never
 * tells who has an Account: it answers alike, in the same time, for a Student, an Admin and an unknown email, and for
 * a Student whose code the per-Account limits withhold.
 */
@Service
public class PasswordResetService {

    private final PasswordCodes passwordCodes;
    private final BreachedPasswords breachedPasswords;
    private final PasswordEncoder passwordEncoder;
    private final SignInService signIns;
    private final Duration codeRequestTime;

    public PasswordResetService(PasswordCodes passwordCodes, BreachedPasswords breachedPasswords,
                                PasswordEncoder passwordEncoder, SignInService signIns,
                                PasswordResetSettings settings) {
        this.passwordCodes = passwordCodes;
        this.breachedPasswords = breachedPasswords;
        this.passwordEncoder = passwordEncoder;
        this.signIns = signIns;
        this.codeRequestTime = settings.codeRequestTime();
    }

    /**
     * Emails a Student a reset code, and sends nothing to any other email. Whatever the email, the request then waits
     * until it has taken the configured time, so that the rows a Student's code writes never show in the answer's
     * timing. Not transactional, so that the wait holds no pooled connection.
     */
    public void sendCode(String email) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        AccountInputRules.requireValid(AccountInputRules.emailViolations(normalizedEmail));
        long started = System.nanoTime();
        try {
            passwordCodes.sendResetCode(normalizedEmail);
        } finally {
            waitUntilTaken(started);
        }
    }

    /**
     * Sets a Student's new password with the reset code, ends every session and opens a new one, and lifts the block
     * on the email's sign-ins. The checks go in this order: the fields, HIBP, then the code, so that a refused new
     * password never spends one of the code's tries. Not transactional: HIBP and bcrypt run before the one
     * transactional step, and would otherwise hold a pooled connection.
     */
    public IssuedSession reset(String email, String code, String newPassword) {
        String normalizedEmail = AccountInputRules.normalizeEmail(email);
        AccountInputRules.requireValid(AccountInputRules.resetViolations(normalizedEmail, code, newPassword));
        breachedPasswords.requireUnbreached("newPassword", newPassword);
        IssuedSession session = passwordCodes.reset(normalizedEmail, code, passwordEncoder.encode(newPassword))
                .orElseThrow(InvalidCodeException::new);
        signIns.clearStudentBlock(normalizedEmail);
        return session;
    }

    /** Elapsed time, not the application's clock: the wait is real, whatever the tests make the clock say. */
    private void waitUntilTaken(long startedNanos) {
        Duration left = codeRequestTime.minusNanos(System.nanoTime() - startedNanos);
        if (left.isPositive()) {
            try {
                Thread.sleep(left);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
