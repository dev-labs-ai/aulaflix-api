package com.devlabs.aulaflix.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.VerificationCodeEntity;
import com.devlabs.aulaflix.domain.entity.VerificationCodeKind;
import com.devlabs.aulaflix.repository.VerificationCodeRepository;

/**
 * The 6-digit codes that reset or change a password, the same rules for both kinds: drawn from a CSPRNG and stored
 * only as an HMAC, each lives 15 minutes, and 5 wrong tries void it. A new code voids the earlier ones of its kind, 60
 * seconds after the latest at the soonest, and an Account gets at most 10 codes, of both kinds together, within any 24
 * hours. Callers hold the Account's row lock, so that one Account's codes are issued and tried one at a time.
 */
@Component
class VerificationCodes {

    private static final Logger log = LoggerFactory.getLogger(VerificationCodes.class);

    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final int TRIES = 5;
    private static final Duration COOLDOWN = Duration.ofSeconds(60);
    private static final Duration CAP_WINDOW = Duration.ofHours(24);
    private static final int CODES_PER_WINDOW = 10;
    private static final int CODES = 1_000_000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VerificationCodeRepository codes;
    private final CodeHmac hmac;
    private final Clock clock;

    VerificationCodes(VerificationCodeRepository codes, CodeHmac hmac, Clock clock) {
        this.codes = codes;
        this.hmac = hmac;
        this.clock = clock;
    }

    /**
     * Issues a new code of the kind and voids the earlier ones, or withholds it past the cooldown or the daily cap.
     * The instants are cut to the microseconds the table keeps, which PostgreSQL would otherwise round, perhaps up,
     * making the code outlive its 15 minutes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Issue issue(AccountEntity account, VerificationCodeKind kind) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<Withheld> withheld = cooldown(account, kind, now).or(() -> dailyCap(account, now));
        if (withheld.isPresent()) {
            return withheld.get();
        }
        int voided = codes.voidUnused(account.getId(), kind, now);
        String code = "%06d".formatted(RANDOM.nextInt(CODES));
        codes.save(new VerificationCodeEntity(account, kind, hmac.of(account.getId(), kind, code), now,
                now.plus(LIFETIME)));
        log.info("Issued a {} code to Account {}, voiding {}", kind, account.getId(), voided);
        return new Issued(code);
    }

    /**
     * Uses the Account's code of the kind, and answers whether it was the one: alive, neither used, voided nor
     * superseded, and right. A wrong code spends one of the live code's tries, and the last try voids it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean redeem(AccountEntity account, VerificationCodeKind kind, String code) {
        Instant now = clock.instant();
        Optional<VerificationCodeEntity> alive = codes.findFirstByAccountIdAndKindOrderByCreatedAtDesc(
                        account.getId(), kind)
                .filter(latest -> latest.getUsedAt() == null && latest.getVoidedAt() == null
                        && now.isBefore(latest.getExpiresAt()));
        if (alive.isEmpty()) {
            return false;
        }
        VerificationCodeEntity latest = alive.get();
        if (hmac.matches(latest.getCodeHmac(), account.getId(), kind, code)) {
            latest.setUsedAt(now);
            return true;
        }
        latest.setFailedAttempts(latest.getFailedAttempts() + 1);
        if (latest.getFailedAttempts() >= TRIES) {
            latest.setVoidedAt(now);
            log.info("Voided Account {}'s {} code after {} wrong tries", account.getId(), kind, TRIES);
        }
        return false;
    }

    private Optional<Withheld> cooldown(AccountEntity account, VerificationCodeKind kind, Instant now) {
        return codes.findFirstByAccountIdAndKindOrderByCreatedAtDesc(account.getId(), kind)
                .map(latest -> latest.getCreatedAt().plus(COOLDOWN))
                .filter(now::isBefore)
                .map(cooledDownAt -> new Withheld("%s code cooldown".formatted(kind), cooledDownAt));
    }

    private Optional<Withheld> dailyCap(AccountEntity account, Instant now) {
        List<VerificationCodeEntity> recent =
                codes.findByAccountIdAndCreatedAtAfterOrderByCreatedAtAsc(account.getId(), now.minus(CAP_WINDOW));
        if (recent.size() < CODES_PER_WINDOW) {
            return Optional.empty();
        }
        return Optional.of(new Withheld("daily codes", recent.getFirst().getCreatedAt().plus(CAP_WINDOW)));
    }

    /** What asking for a code came to. */
    sealed interface Issue permits Issued, Withheld {
    }

    /** A new code, to email to the Account and never to log. */
    record Issued(String code) implements Issue {
    }

    /** No code, because of the limit named, until the instant. */
    record Withheld(String limit, Instant until) implements Issue {
    }
}
