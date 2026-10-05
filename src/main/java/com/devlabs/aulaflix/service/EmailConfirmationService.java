package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.EmailConfirmationTokenEntity;
import com.devlabs.aulaflix.exception.EmailAlreadyConfirmedException;
import com.devlabs.aulaflix.exception.InvalidConfirmationLinkException;
import com.devlabs.aulaflix.exception.RateLimitedException;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.EmailConfirmationTokenRepository;

/**
 * Confirmation links: a Student proves the email theirs by following one, from any device. Nothing is gated on it.
 */
@Service
public class EmailConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(EmailConfirmationService.class);

    private static final Duration LINK_LIFETIME = Duration.ofHours(72);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final Duration RESEND_WINDOW = Duration.ofHours(24);
    private static final int RESENDS_PER_WINDOW = 5;

    private final AccountRepository accounts;
    private final EmailConfirmationTokenRepository tokens;
    private final EmailTemplates templates;
    private final EmailOutbox outbox;
    private final Clock clock;

    public EmailConfirmationService(AccountRepository accounts, EmailConfirmationTokenRepository tokens,
                                    EmailTemplates templates, EmailOutbox outbox, Clock clock) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.templates = templates;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Queues the link that sign-up sends, which is also the welcome email, in the sign-up's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void sendFirstLink(AccountEntity student) {
        sendLink(student, false);
    }

    /**
     * Confirms the email of the link's Account, and signs no one in. A link already used says confirmed again until it
     * expires; one that is unknown, expired or voided by a newer one is refused alike.
     */
    @Transactional
    public void confirm(String token) {
        Instant now = clock.instant();
        EmailConfirmationTokenEntity link = tokens.findByTokenHash(SecretTokens.hash(token))
                .filter(found -> found.getVoidedAt() == null && now.isBefore(found.getExpiresAt()))
                .orElseThrow(InvalidConfirmationLinkException::new);
        if (link.getUsedAt() == null) {
            link.setUsedAt(now);
            link.getAccount().confirmEmail(now);
            log.info("Account {} confirmed its email", link.getAccount().getId());
        }
    }

    /**
     * Sends the Student a new link and voids the previous ones. Resends of one Account go one at a time, under a lock
     * on its row, so that none slips past the limits counted over the links already sent: 60 seconds after the latest
     * link, sign-up's included, and 5 resends within any 24 hours.
     */
    @Transactional
    public void resend(long accountId) {
        AccountEntity student = accounts.findByIdForUpdate(accountId).orElseThrow();
        if (student.getEmailConfirmedAt() != null) {
            throw new EmailAlreadyConfirmedException();
        }
        Instant now = clock.instant();
        requireCooledDown(student, now);
        requireUnderTheDailyCap(student, now);
        int voided = tokens.voidUnused(accountId, now);
        sendLink(student, true);
        log.info("Account {} asked for a new confirmation link, voiding {}", accountId, voided);
    }

    private void requireCooledDown(AccountEntity student, Instant now) {
        tokens.findFirstByAccountIdOrderByCreatedAtDesc(student.getId())
                .map(latest -> latest.getCreatedAt().plus(RESEND_COOLDOWN))
                .filter(now::isBefore)
                .ifPresent(cooledDownAt -> refuse(student, "resend cooldown", now, cooledDownAt));
    }

    private void requireUnderTheDailyCap(AccountEntity student, Instant now) {
        List<EmailConfirmationTokenEntity> resends = tokens
                .findByAccountIdAndResendTrueAndCreatedAtAfterOrderByCreatedAtAsc(student.getId(),
                        now.minus(RESEND_WINDOW));
        if (resends.size() >= RESENDS_PER_WINDOW) {
            refuse(student, "daily resends", now, resends.getFirst().getCreatedAt().plus(RESEND_WINDOW));
        }
    }

    /** Named after the Account's id, never its email: the refusal goes into the log. */
    private static void refuse(AccountEntity student, String limit, Instant now, Instant until) {
        throw new RateLimitedException("Limit on %s reached by Account %d".formatted(limit, student.getId()),
                Duration.between(now, until), true);
    }

    /**
     * The instants are cut to the microseconds the table keeps, which PostgreSQL would otherwise round, perhaps up,
     * making the link outlive its 72 hours.
     */
    private void sendLink(AccountEntity student, boolean resend) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String token = SecretTokens.newToken();
        tokens.save(new EmailConfirmationTokenEntity(SecretTokens.hash(token), student, resend, now,
                now.plus(LINK_LIFETIME)));
        outbox.enqueue(templates.confirmationLink(student.getEmail(), student.getName(), token));
    }
}
