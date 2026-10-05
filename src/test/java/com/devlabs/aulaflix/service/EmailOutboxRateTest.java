package com.devlabs.aulaflix.service;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.SmtpRelay;
import com.devlabs.aulaflix.StudentApi;

/**
 * The drainer never starts more than 2 sends within any second here, however often it is called, a failed one
 * included. A rate this low needs an application of its own, with a Mailpit of its own, so that no other test's
 * emails share it. Each test runs on a day of its own, so the sends of the other never fall in its second.
 */
class EmailOutboxRateTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private SmtpRelay smtp;

    @DynamicPropertySource
    static void sendTwoEmailsASecond(DynamicPropertyRegistry registry) {
        registry.add("aulaflix.outbox.send-rate.emails", () -> "2");
        registry.add("aulaflix.outbox.send-rate.per", () -> "1s");
    }

    @AfterEach
    void bringTheSmtpServerBack() {
        smtp.open();
    }

    @Test
    void neverSendsMoreThanTwoWithinASecond() {
        Instant start = clock.instant().plus(Duration.ofDays(1));
        clock.set(start);
        List<String> emails = signUpStudents(5);

        assertThat(outbox.drain()).isEqualTo(2);
        assertThat(outbox.drain()).isZero();
        clock.set(start.plusMillis(999));
        assertThat(outbox.drain()).isZero();
        clock.set(start.plusSeconds(1));
        assertThat(outbox.drain()).isEqualTo(2);
        clock.set(start.plusSeconds(2));
        assertThat(outbox.drain()).isOne();

        assertThat(emails).allSatisfy(email -> assertThat(mailpit.to(email)).hasSize(1));
    }

    @Test
    void countsAFailedAttemptAgainstTheRate() {
        Instant start = clock.instant().plus(Duration.ofDays(2));
        clock.set(start);
        List<String> emails = signUpStudents(3);
        smtp.takeDown();

        assertThat(outbox.drain()).isZero();
        smtp.open();

        assertThat(outbox.drain()).isOne();
        clock.set(start.plusSeconds(1));
        assertThat(outbox.drain()).isOne();
        clock.set(start.plus(Duration.ofMinutes(1)));
        assertThat(outbox.drain()).isOne();
        assertThat(emails).allSatisfy(email -> assertThat(mailpit.to(email)).hasSize(1));
    }

    private List<String> signUpStudents(int students) {
        return IntStream.range(0, students).mapToObj(student -> {
            String email = newEmail();
            new StudentApi(mvc).signedUp(email, PASSWORD);
            return email;
        }).toList();
    }
}
