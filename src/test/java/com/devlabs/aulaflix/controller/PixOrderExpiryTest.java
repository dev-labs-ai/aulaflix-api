package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminEnrollments;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredOrders;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.OrderExpiry;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * A Pix Order expires 30 minutes after it was placed. The expiry job re-reads the charge from Asaas, the WireMock stub,
 * first, and a payment wins; otherwise the Order is {@code EXPIRED}, its charge is deleted, and nothing is emailed.
 */
class PixOrderExpiryTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PIX_PRICE_CENTS = 44730;
    private static final Duration PIX_LIFETIME = Duration.ofMinutes(30);

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private OrderExpiry expiry;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private WebhookWorker worker;

    @Autowired
    private JdbcTemplate jdbc;

    private AdminCourses courses;

    private String adminEmail;

    private String studentEmail;

    private StudentOrders orders;

    private long course;

    private AsaasWebhooks webhooks;

    @BeforeEach
    void putACourseOnSaleAndSignInAStudent() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD), storedVideos);
        course = courses.onSale(newSlug());
        webhooks = new AsaasWebhooks(mvc);
        BffApi bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(studentEmail, PASSWORD));
    }

    /**
     * A job pays whatever Order of the whole suite is due, and a Duplicate payment it finds alerts every Admin the
     * suite has made, hundreds of them: what it queued is discarded, so that no later test's drain waits behind it.
     */
    @AfterEach
    void discardWhatTheJobsQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void expiresAnUnpaidPixAfterThirtyMinutesDeletingItsChargeAndEmailingNothing() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "PENDING");
        clock.set(clock.instant().plus(PIX_LIFETIME));

        expiry.expireDue();
        sendTheStudentsEmails();

        assertThat(orders.get(code)).hasStatusOk().bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("EXPIRED");
            assertThat(order).doesNotHavePath("$.pix");
        });
        assertThat(asaas.deletionsOf(charge)).isOne();
        assertThat(orders.list()).bodyJson().extractingPath("$.items").asArray().isEmpty();
        assertThat(emailsButTheWelcome()).isEmpty();
    }

    @Test
    void leavesAPixAwaitingPaymentUntilItsThirtyMinutesAreUp() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "PENDING");
        clock.set(clock.instant().plus(PIX_LIFETIME).minusSeconds(1));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
        assertThat(asaas.readsOf(charge)).isZero();
        assertThat(asaas.deletionsOf(charge)).isZero();
    }

    /** The Student who wants to pay again gets a new Order, with a new charge and its QR code, in one click. */
    @Test
    void letsTheStudentPlaceANewPixOnceTheirsExpired() {
        String expired = orders.placedPix(course, Cpfs.newCpf());
        chargeIs(expired, "PENDING");
        clock.set(clock.instant().plus(PIX_LIFETIME));
        expiry.expireDue();

        MvcTestResult placed = orders.placePix(course, null);

        assertThat(placed).hasStatus(HttpStatus.CREATED);
        String code = StudentOrders.codeOf(placed);
        assertThat(code).isNotEqualTo(expired);
        assertThat(placed).bodyJson().extractingPath("$.pix.copyPasteCode")
                .isEqualTo(Asaas.copyPasteCodeOf(Asaas.chargeOf(code)));
    }

    /** The webhook was lost, or has yet to arrive: the re-read finds the payment, which wins. */
    @Test
    void paysAnOrderWhoseChargeAsaasShowsPaidAndGrantsItsEnrollment() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "RECEIVED");
        clock.set(clock.instant().plus(PIX_LIFETIME));
        Instant paidAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

        expiry.expireDue();
        sendTheStudentsEmails();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "paidAt": "%s"}""".formatted(paidAt));
        assertThat(asaas.deletionsOf(charge)).isZero();
        assertThat(studentsEnrollments()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "ORDER", "orderCode": "%s", "course": {"id": %d}}],
                 "totalItems": 1}""".formatted(code, course));
        assertThat(emailsButTheWelcome()).singleElement().extracting(Mailpit.Email::subject)
                .asString().startsWith("Compra confirmada");
    }

    /** The Student paid as the Order expired, before Asaas deleted the charge: the payment still wins. */
    @Test
    void paysAnExpiredOrderWhosePaymentAsaasConfirmsAfterwards() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "PENDING");
        clock.set(clock.instant().plus(PIX_LIFETIME));
        expiry.expireDue();
        chargeIs(code, "CONFIRMED");

        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS,
                code));
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
        assertThat(studentsEnrollments()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "ORDER", "orderCode": "%s"}], "totalItems": 1}"""
                .formatted(code));
    }

    @Test
    void leavesTheOrderAwaitingPaymentWhileAsaasCannotBeReachedAndExpiresItOnTheNextRun() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "PENDING");
        asaas.answerNextChargeReadWith(charge, Asaas.tooLate());
        clock.set(clock.instant().plus(PIX_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
        assertThat(asaas.deletionsOf(charge)).isZero();

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
        assertThat(asaas.deletionsOf(charge)).isOne();
    }

    /** A charge left payable must not belong to an expired Order: the deletion comes first. */
    @Test
    void leavesTheOrderAwaitingPaymentWhileAsaasCannotDeleteTheChargeAndExpiresItOnTheNextRun() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "PENDING");
        asaas.answerNextDeletionOf(charge, serverError());
        clock.set(clock.instant().plus(PIX_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
        assertThat(asaas.deletionsOf(charge)).isEqualTo(2);
    }

    /** The Student already has the Course: the payment the re-read finds is a Duplicate payment, alerting Admins. */
    @Test
    void paysAnOrderAsaasShowsPaidWhileTheCourseIsHeldAsADuplicatePayment() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "CONFIRMED");
        new AdminEnrollments(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD)).granted(studentEmail, course);
        clock.set(clock.instant().plus(PIX_LIFETIME));

        expiry.expireDue();
        List<String> queuedAlerts = new StoredOutboxEmails(jdbc).recipientsOf("DUPLICATE_PAYMENT_ALERT", code);
        sendTheStudentsEmails();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "duplicatePayment": true}""");
        assertThat(queuedAlerts).contains(adminEmail);
        assertThat(asaas.deletionsOf(charge)).isZero();
        assertThat(studentsEnrollments()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "MANUAL"}], "totalItems": 1}""");
        assertThat(emailsButTheWelcome()).isEmpty();
    }

    /** A charge Asaas does not know cannot be paid, and leaves nothing to delete. */
    @Test
    void expiresAnOrderWhoseChargeAsaasRefusesToReRead() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.answerChargeReadsWith(charge, Asaas.error(404, "invalid_payment"));
        clock.set(clock.instant().plus(PIX_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
        assertThat(asaas.deletionsOf(charge)).isZero();
    }

    @Test
    void expiresAnOrderWhoseChargeIsDeletedWithoutDeletingItAgain() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, "PENDING", PIX_PRICE_CENTS, code, true);
        clock.set(clock.instant().plus(PIX_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
        assertThat(asaas.deletionsOf(charge)).isZero();
    }

    /** A retry would be refused the same way; should the charge be paid after all, its payment still wins. */
    @Test
    void expiresTheOrderWhenAsaasRefusesToDeleteTheCharge() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "PENDING");
        asaas.answerNextDeletionOf(charge, Asaas.error(400, "invalid_action"));
        clock.set(clock.instant().plus(PIX_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
        assertThat(asaas.deletionsOf(charge)).isOne();
    }

    /**
     * The API stopped between making the charge and keeping its id, so the Student never saw its QR code: every charge
     * under the Order's code is deleted.
     */
    @Test
    void deletesTheChargesUnderTheCodeOfAnOrderThatNeverGotItsChargesId() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        new StoredOrders(jdbc).forgetCharge(code);
        clock.set(clock.instant().plus(PIX_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isOne();
    }

    /**
     * Sends what is queued to the Student, and nothing else: a job pays whatever Order of the whole suite is due, and
     * a Duplicate payment alerts every Admin the suite has made, hundreds of them.
     */
    private void sendTheStudentsEmails() {
        new StoredOutboxEmails(jdbc).discardPendingExceptTo(studentEmail);
        outbox.drain();
    }

    /** The Student's Enrollments, as an Admin signed in now finds them, whatever the clock did to earlier sessions. */
    private MvcTestResult studentsEnrollments() {
        return new AdminEnrollments(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD))
                .list("email=" + studentEmail);
    }

    private String chargeIs(String code, String status) {
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, status, PIX_PRICE_CENTS, code, false);
        return charge;
    }

    /** What the Student was emailed, but the confirmation link every new Account gets. */
    private List<Mailpit.Email> emailsButTheWelcome() {
        return mailpit.to(studentEmail).stream()
                .filter(email -> !email.subject().startsWith("Boas-vindas"))
                .toList();
    }
}
