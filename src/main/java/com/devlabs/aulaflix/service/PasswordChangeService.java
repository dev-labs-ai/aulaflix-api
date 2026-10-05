package com.devlabs.aulaflix.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.exception.InvalidCodeException;

/**
 * A signed-in Student changes their password with a 6-digit code sent to their email. The change keeps the session it
 * came with and ends every other, so that a stolen session dies with the old password.
 */
@Service
public class PasswordChangeService {

    private final PasswordCodes passwordCodes;
    private final BreachedPasswords breachedPasswords;
    private final PasswordEncoder passwordEncoder;

    public PasswordChangeService(PasswordCodes passwordCodes, BreachedPasswords breachedPasswords,
                                 PasswordEncoder passwordEncoder) {
        this.passwordCodes = passwordCodes;
        this.breachedPasswords = breachedPasswords;
        this.passwordEncoder = passwordEncoder;
    }

    /** Emails the Student a change code, or refuses with 429 within the cooldown or past the daily cap. */
    public void sendCode(long accountId) {
        passwordCodes.sendChangeCode(accountId);
    }

    /**
     * Sets the Student's new password with the change code, and ends every session of the Account but the one the
     * change came with. The checks go in this order: the fields, HIBP, then the code, so that a refused new password
     * never spends one of the code's tries. Not transactional: HIBP and bcrypt run before the one transactional step,
     * and would otherwise hold a pooled connection.
     */
    public void change(AuthenticatedAccount student, String code, String newPassword) {
        AccountInputRules.requireValid(AccountInputRules.changeViolations(code, newPassword));
        breachedPasswords.requireUnbreached("newPassword", newPassword);
        if (!passwordCodes.change(student.accountId(), student.sessionId(), code,
                passwordEncoder.encode(newPassword))) {
            throw new InvalidCodeException();
        }
    }
}
