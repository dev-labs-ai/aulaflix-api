package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminEnrollments;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * What the webhook leaves in the log: the event ids, the charges and the Orders, never the token, a body, nor the
 * Student's email. A body that is no event is a WARN, and so are a re-read that contradicts its Order and a Duplicate
 * payment; a re-read Asaas refuses is an ERROR, with what Asaas said.
 */
@ExtendWith(OutputCaptureExtension.class)
class AsaasWebhookLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PIX_PRICE_CENTS = 44730;

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private WebhookWorker worker;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminEmail;

    private String adminToken;

    private long course;

    private AsaasWebhooks webhooks;

    @BeforeEach
    void putACourseOnSale() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        adminToken = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        course = new AdminCourses(mvc, adminToken, storedVideos).onSale(newSlug());
        webhooks = new AsaasWebhooks(mvc);
    }

    @Test
    void logsAPaymentByItsOrderWithoutTheTokenTheBodyOrTheEmail(CapturedOutput output) {
        String email = StudentApi.newEmail();
        String code = signedUp(email).placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, "CONFIRMED", PIX_PRICE_CENTS, code, false);
        String eventId = newEventId();
        String marker = "marker-" + UUID.randomUUID();
        String event = paymentEvent(eventId, "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS, code)
                .replace("\"dateCreated\": \"2026-10-05\"", "\"description\": \"%s\"".formatted(marker));

        webhooks.deliver("wrong-" + Asaas.WEBHOOK_TOKEN, event);
        webhooks.deliver(event);
        webhooks.deliver(event);
        webhooks.deliver("{\"note\": \"%s\"}".formatted(marker));
        worker.processPending();
        outbox.drain();

        assertThat(output.getAll())
                .contains("Refused POST /v1/webhooks/asaas: invalid-webhook-token",
                        "Stored Asaas webhook event %s (PAYMENT_CONFIRMED) as PENDING".formatted(eventId),
                        "Asaas webhook event %s was received before".formatted(eventId),
                        "Order %s was paid".formatted(code))
                .containsPattern("Order %s granted Enrollment \\d+ to Student \\d+ in Course %d".formatted(code, course))
                .doesNotContain(Asaas.WEBHOOK_TOKEN, marker, email);
    }

    /**
     * The alert goes to every Admin; the log names the Order and the Student by id, never an Admin's email. The alerts
     * go unsent: they would go to every Admin the whole suite has made.
     */
    @Test
    void logsADuplicatePaymentAtWarnWithoutAnyEmail(CapturedOutput output) {
        String email = StudentApi.newEmail();
        String code = signedUp(email).placedPix(course, Cpfs.newCpf());
        new AdminEnrollments(mvc, adminToken).granted(email, course);
        asaas.chargeIs(Asaas.chargeOf(code), "CONFIRMED", PIX_PRICE_CENTS, code, false);

        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", Asaas.chargeOf(code), "CONFIRMED",
                PIX_PRICE_CENTS, code));
        worker.processPending();
        new StoredOutboxEmails(jdbc).discardPending();

        assertThat(output.getAll())
                .containsPattern("WARN .*Order %s is a Duplicate payment: Student \\d+ already has Course %d"
                        .formatted(code, course))
                .contains("(DUPLICATE_PAYMENT_ALERT)")
                .doesNotContain(email, adminEmail);
    }

    @Test
    void logsABodyThatIsNoEventAtWarn(CapturedOutput output) {
        webhooks.deliver("not an event");

        assertThat(output.getAll()).containsPattern(
                "WARN .*Stored an Asaas webhook delivery the API cannot process \\(12 bytes\\): not JSON");
    }

    @Test
    void logsAReReadThatContradictsTheOrderAtWarn(CapturedOutput output) {
        String code = signedUp(StudentApi.newEmail()).placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, "CONFIRMED", PIX_PRICE_CENTS + 100, code, false);

        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS, code));
        worker.processPending();

        assertThat(output.getAll()).containsPattern("WARN .*Asaas charge " + Pattern.quote(charge)
                + " does not match Order " + code);
    }

    @Test
    void logsWhyAChargeIsNotPaid(CapturedOutput output) {
        String pending = signedUp(StudentApi.newEmail()).placedPix(course, Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(pending), "PENDING", PIX_PRICE_CENTS, pending, false);
        String deleted = signedUp(StudentApi.newEmail()).placedPix(course, Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(deleted), "RECEIVED", PIX_PRICE_CENTS, deleted, true);

        for (String code : new String[] {pending, deleted}) {
            webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_RECEIVED", Asaas.chargeOf(code), "RECEIVED",
                    PIX_PRICE_CENTS, code));
        }
        worker.processPending();

        assertThat(output.getAll()).contains(
                "Asaas charge %s of Order %s is PENDING, so not paid".formatted(Asaas.chargeOf(pending), pending),
                "Asaas charge %s of Order %s is deleted, so not paid".formatted(Asaas.chargeOf(deleted), deleted));
    }

    @Test
    void logsAReReadAsaasRefusesAtErrorWithWhatAsaasSaid(CapturedOutput output) {
        String code = signedUp(StudentApi.newEmail()).placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.answerChargeReadsWith(charge, Asaas.error(404, "invalid_payment"));

        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS, code));
        worker.processPending();

        assertThat(output.getAll()).containsPattern(
                "ERROR .*Webhook event \\d+ is unprocessable: Asaas refused reading a charge: HTTP 404 "
                        + "\\[invalid_payment]");
    }

    @Test
    void logsAnAsaasOutageAtWarn(CapturedOutput output) {
        String code = signedUp(StudentApi.newEmail()).placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, "CONFIRMED", PIX_PRICE_CENTS, code, false);
        asaas.answerNextChargeReadWith(charge, Asaas.tooLate());

        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS, code));
        worker.processPending();
        worker.processPending();

        assertThat(output.getAll()).containsPattern(
                "WARN .*Left webhook event \\d+ and the rest pending: Asaas failed reading a charge");
    }

    /** Money going back is logged by Order code, with the reason the Enrollment ended, never with the Student's email. */
    @Test
    void logsMoneyGoingBackByItsOrderWithoutTheEmail(CapturedOutput output) {
        String email = StudentApi.newEmail();
        String refunded = paid(email);
        asaas.chargeIsRefunded(Asaas.chargeOf(refunded), "DONE", PIX_PRICE_CENTS, refunded);
        String chargedBack = paid(StudentApi.newEmail());
        asaas.chargeShows(Asaas.chargeOf(chargedBack), "CHARGEBACK_REQUESTED", PIX_PRICE_CENTS, chargedBack, "");
        String blocked = paid(StudentApi.newEmail());
        asaas.chargeShows(Asaas.chargeOf(blocked), "REFUNDED", PIX_PRICE_CENTS, blocked, "");

        for (String code : new String[] {refunded, chargedBack, blocked}) {
            webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_REFUNDED", Asaas.chargeOf(code), "REFUNDED",
                    PIX_PRICE_CENTS, code));
        }
        worker.processPending();
        new StoredOutboxEmails(jdbc).discardPending();

        assertThat(output.getAll())
                .contains("Order %s is refunding: its charge %s shows a refund the API did not ask for"
                                .formatted(refunded, Asaas.chargeOf(refunded)),
                        "Order %s was refunded".formatted(refunded),
                        "Order %s was reversed by CHARGEBACK".formatted(chargedBack),
                        "Order %s was reversed by PIX_BLOCK_UPHELD".formatted(blocked))
                .containsPattern("Order %s ended Enrollment \\d+ with REFUND".formatted(refunded))
                .containsPattern("Order %s ended Enrollment \\d+ with CHARGEBACK".formatted(chargedBack))
                .containsPattern("Order %s ended Enrollment \\d+ with PIX_BLOCK_UPHELD".formatted(blocked))
                .doesNotContain(email);
    }

    /** A Pix Order the Student with the email placed, paid: its webhook came, and the worker ran. */
    private String paid(String email) {
        String code = signedUp(email).placedPix(course, Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(code), "CONFIRMED", PIX_PRICE_CENTS, code, false);
        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", Asaas.chargeOf(code), "CONFIRMED",
                PIX_PRICE_CENTS, code));
        worker.processPending();
        return code;
    }

    private StudentOrders signedUp(String email) {
        BffApi bff = new BffApi(mvc);
        return new StudentOrders(bff, new StudentApi(bff).signedUp(email, PASSWORD));
    }
}
