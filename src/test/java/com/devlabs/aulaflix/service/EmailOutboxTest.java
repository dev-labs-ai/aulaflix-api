package com.devlabs.aulaflix.service;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.SmtpRelay;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredOutboxEmails.StoredOutboxEmail;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.TestcontainersConfiguration;
import com.devlabs.aulaflix.domain.entity.EmailTemplate;

/**
 * Slow SMTP never fails a request nor loses a message: a request only queues, and an email the server failed is sent
 * on a later drain. Each test starts from an empty queue: other tests queue emails under clocks of their own, which
 * would fall due here as the clock moves, and be the ones a drain sends, counts or stops at.
 */
class EmailOutboxTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private SmtpRelay smtp;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private StoredOutboxEmails stored;

    @BeforeEach
    void startFromAnEmptyQueue() {
        stored = new StoredOutboxEmails(jdbc);
        stored.discardPending();
    }

    @AfterEach
    void bringTheSmtpServerBack() {
        smtp.open();
    }

    @Test
    void signsUpWhileSmtpIsDownAndSendsOnALaterDrain() {
        smtp.takeDown();
        String email = newEmail();

        assertThat(new StudentApi(mvc).signUp("Bia", email, PASSWORD)).hasStatus(HttpStatus.CREATED);
        outbox.drain();

        Instant failedAt = clock.instant();
        assertThat(mailpit.to(email)).isEmpty();
        assertThat(stored.onlyOneTo(email)).satisfies(queued -> {
            assertThat(queued.template()).isEqualTo("CONFIRMATION_LINK");
            assertThat(queued.state()).isEqualTo("PENDING");
            assertThat(queued.attempts()).isOne();
            assertThat(queued.nextAttemptAt())
                    .isCloseTo(failedAt.plus(Duration.ofMinutes(1)), within(1, ChronoUnit.MICROS));
            assertThat(queued.sentAt()).isNull();
        });

        smtp.open();
        clock.set(failedAt.plus(Duration.ofMinutes(1)).minusSeconds(1));
        outbox.drain();
        assertThat(mailpit.to(email)).isEmpty();

        clock.set(failedAt.plus(Duration.ofMinutes(1)));
        outbox.drain();
        assertThat(mailpit.to(email)).hasSize(1);
        assertThat(stored.onlyOneTo(email)).satisfies(sent -> {
            assertThat(sent.state()).isEqualTo("SENT");
            assertThat(sent.attempts()).isEqualTo(2);
            assertThat(sent.sentAt()).isCloseTo(clock.instant(), within(1, ChronoUnit.MICROS));
        });
    }

    @Test
    void signsUpAtOnceWhileSmtpIsSilentAndGivesUpOnItAfterTheTimeout() {
        smtp.slowDown();
        String email = newEmail();

        long signUpStart = System.nanoTime();
        assertThat(new StudentApi(mvc).signUp("Bia", email, PASSWORD)).hasStatus(HttpStatus.CREATED);
        assertThat(Duration.ofNanos(System.nanoTime() - signUpStart))
                .isLessThan(TestcontainersConfiguration.SMTP_TIMEOUT);

        assertThat(outbox.drain()).isZero();
        assertThat(stored.onlyOneTo(email).attempts()).isOne();

        smtp.open();
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        outbox.drain();
        assertThat(mailpit.to(email)).hasSize(1);
    }

    @Test
    void waitsTwiceAsLongAfterEachFailureUpToAnHour() {
        smtp.takeDown();
        String email = newEmail();
        new StudentApi(mvc).signedUp(email, PASSWORD);

        List<Duration> waits = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
                Duration.ofMinutes(8), Duration.ofMinutes(16), Duration.ofMinutes(32), Duration.ofHours(1),
                Duration.ofHours(1));
        for (int failure = 0; failure < waits.size(); failure++) {
            assertThat(outbox.drain()).isZero();
            StoredOutboxEmail queued = stored.onlyOneTo(email);
            assertThat(queued.attempts()).isEqualTo(failure + 1);
            assertThat(queued.nextAttemptAt())
                    .isCloseTo(clock.instant().plus(waits.get(failure)), within(1, ChronoUnit.MICROS));
            clock.set(queued.nextAttemptAt());
        }

        smtp.open();
        outbox.drain();
        assertThat(mailpit.to(email)).hasSize(1);
    }

    @Test
    void stopsAtTheFirstFailureAndLeavesTheRestDue() {
        String first = newEmail();
        String second = newEmail();
        new StudentApi(mvc).signedUp(first, PASSWORD);
        new StudentApi(mvc).signedUp(second, PASSWORD);
        smtp.takeDown();

        assertThat(outbox.drain()).isZero();

        assertThat(stored.onlyOneTo(first).attempts()).isOne();
        assertThat(stored.onlyOneTo(second).attempts()).isZero();
        smtp.open();
        assertThat(outbox.drain()).isOne();
        assertThat(mailpit.to(second)).hasSize(1);
    }

    @Test
    void sendsAnEmailWithItsOwnHeadersFromAulaFlix() {
        String email = newEmail();
        transactions.executeWithoutResult(transaction -> outbox.enqueue(new OutboundEmail(
                EmailTemplate.CONFIRMATION_LINK, email, "Assunto", "Corpo do email, com acentuação.\n",
                Map.of("List-Unsubscribe", "<http://localhost:3001/api/waitlist/unsubscribe?token=abc>"))));

        assertThat(outbox.drain()).isOne();

        assertThat(mailpit.to(email)).singleElement().satisfies(sent -> {
            assertThat(sent.from()).isEqualTo("AulaFlix <contato@aulaflix.com.br>");
            assertThat(sent.subject()).isEqualTo("Assunto");
            assertThat(sent.text()).isEqualTo("Corpo do email, com acentuação.\r\n");
            assertThat(sent.headers()).containsEntry("List-Unsubscribe",
                    List.of("<http://localhost:3001/api/waitlist/unsubscribe?token=abc>"));
        });
    }
}
