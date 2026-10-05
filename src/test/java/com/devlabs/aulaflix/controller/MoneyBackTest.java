package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminEnrollments;
import com.devlabs.aulaflix.AdminOrders;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredOrders;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StoredWebhookEvents;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.OrderReconciliation;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * Access follows the money, whoever moves it: once a re-read of the charge at Asaas, the WireMock stub, shows money gone
 * back, by a refund made in the Asaas UI, a chargeback or an upheld Pix cautionary block, the Order and the Enrollment
 * it granted follow, whether the webhook brings it or reconciliation finds it.
 */
class MoneyBackTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PIX_PRICE_CENTS = 44730;
    private static final String COURSE_TITLE = "Backend com Node.js";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private WebhookWorker worker;

    @Autowired
    private OrderReconciliation reconciliation;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${aulaflix.asaas.paid-recheck-interval}")
    private Duration paidRecheckInterval;

    private String adminEmail;

    private AdminCourses courses;

    private AdminOrders adminOrders;

    private AdminEnrollments enrollments;

    private BffApi bff;

    private String studentEmail;

    private String studentToken;

    private StudentOrders orders;

    private long course;

    private long lesson;

    @BeforeEach
    void putACourseOnSaleAndSignInAStudent() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        String adminToken = signInTheAdmin();
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        studentToken = new StudentApi(bff).signedUp(studentEmail, PASSWORD);
        orders = new StudentOrders(bff, studentToken);
        course = courses.onSale(newSlug());
        lesson = courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"), "Rotas no Express",
                "rotas-no-express", "five-seconds.mp4");
    }

    /** The jobs follow every Order of the whole suite: what they queued is discarded. */
    @AfterEach
    void discardWhatTheJobsQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    /** The Admin refunded in the Asaas UI: the API never asked, and follows all the same. */
    @Test
    void aRefundMadeInTheAsaasUiRefundsTheOrderEndsItsEnrollmentAndEmailsTheStudentOnce() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        clock.set(clock.instant().plusSeconds(90));
        Instant seenAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        asaas.chargeIsRefunded(charge, "PENDING", PIX_PRICE_CENTS, code);

        deliver("PAYMENT_REFUNDED", charge, "REFUNDED", code);
        deliver("PAYMENT_REFUNDED", charge, "REFUNDED", code);
        worker.processPending();
        sendTheStudentsEmails();

        MvcTestResult order = adminOrders.get(code);
        assertThat(order).bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "REFUNDING", "refundRequestedAt": "%s",
                 "enrollment": {"status": "ENDED", "endedAt": "%s", "endReason": "REFUND"}}"""
                .formatted(code, seenAt, seenAt));
        assertThat(order).bodyJson().doesNotHavePath("$.refundRequestedBy");
        assertThat(order).bodyJson().doesNotHavePath("$.refundedAt");
        assertThat(playback()).hasStatus(HttpStatus.CONFLICT);
        assertThat(orders.list()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "status": "REFUNDING"}]}""".formatted(code));
        assertThat(refundEmails()).singleElement().satisfies(email -> {
            assertThat(email.subject()).isEqualTo("Reembolso do pedido " + code);
            assertThat(email.text()).contains("pedido " + code, "R$ 447,30", COURSE_TITLE);
        });
        assertThat(asaas.refundsOf(charge)).isZero();
    }

    @Test
    void anAdminRefundAfterTheRefundMadeInTheAsaasUiAnswersTheOrderWithoutAskingAsaas() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.chargeIsRefunded(charge, "PENDING", PIX_PRICE_CENTS, code);
        deliver("PAYMENT_REFUNDED", charge, "REFUNDED", code);
        worker.processPending();

        MvcTestResult refund = adminOrders.refund(code);

        assertThat(refund).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("REFUNDING");
        assertThat(asaas.refundsOf(charge)).isZero();
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).containsExactly(studentEmail);
    }

    @Test
    void aChargebackReversesTheOrderAndEndsItsEnrollmentWithoutEmailingTheStudent() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        clock.set(clock.instant().plusSeconds(90));
        Instant seenAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        asaas.chargeShows(charge, "CHARGEBACK_REQUESTED", PIX_PRICE_CENTS, code, """
                "chargeback": {"status": "REQUESTED", "reason": "FRAUD"}""");

        deliver("PAYMENT_CHARGEBACK_REQUESTED", charge, "CHARGEBACK_REQUESTED", code);
        worker.processPending();

        MvcTestResult order = adminOrders.get(code);
        assertThat(order).bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "REVERSED",
                 "enrollment": {"status": "ENDED", "endedAt": "%s", "endReason": "CHARGEBACK"}}"""
                .formatted(code, seenAt));
        assertThat(order).bodyJson().doesNotHavePath("$.refundRequestedAt");
        assertThat(playback()).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/enrollment-required");
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).isEmpty();
    }

    @Test
    void aRefundTheChargeShowsDoneRefundsTheOrderAtOnce() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        Instant seenAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        asaas.chargeIsRefunded(charge, "DONE", PIX_PRICE_CENTS, code);

        deliver("PAYMENT_REFUNDED", charge, "REFUNDED", code);
        worker.processPending();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDED", "refundRequestedAt": "%s", "refundedAt": "%s",
                 "enrollment": {"status": "ENDED", "endReason": "REFUND"}}""".formatted(seenAt, seenAt));
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).containsExactly(studentEmail);
    }

    /** The Admin's refund needs no reconciliation run to be done: the webhook Asaas sends when it is brings it. */
    @Test
    void theWebhookOfARefundTheAdminMadeRefundsTheOrderOnceItIsDone() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        assertThat(adminOrders.refund(code)).hasStatusOk();
        clock.set(clock.instant().plusSeconds(90));
        Instant doneAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        asaas.chargeIsRefunded(charge, "DONE", PIX_PRICE_CENTS, code);

        deliver("PAYMENT_REFUNDED", charge, "REFUNDED", code);
        worker.processPending();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDED", "refundedAt": "%s", "refundRequestedBy": {"name": "Ana"}}"""
                .formatted(doneAt));
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).containsExactly(studentEmail);
    }

    /**
     * The Admin's refund timed out after Asaas took it, so the Order was left paid: the refund the charge shows is
     * the Admin's all the same, and its webhook makes the Order refunding.
     */
    @Test
    void aRefundAsaasTookAfterTheAdminStoppedWaitingIsPickedUpByItsWebhook() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.answerNextRefundOf(charge, Asaas.tooLate());
        assertThat(adminOrders.refund(code)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        asaas.chargeShows(charge, "REFUND_REQUESTED", PIX_PRICE_CENTS, code, "");

        deliver("PAYMENT_REFUND_IN_PROGRESS", charge, "REFUND_REQUESTED", code);
        worker.processPending();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDING", "enrollment": {"status": "ENDED", "endReason": "REFUND"}}""");
        assertThat(playback()).hasStatus(HttpStatus.CONFLICT);
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).containsExactly(studentEmail);
    }

    /** Asaas cancelled the refund: no money went back, so nothing changes. */
    @Test
    void aRefundAsaasCancelledChangesNothing() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.chargeShows(charge, "CONFIRMED", PIX_PRICE_CENTS, code, """
                "refunds": [{"dateCreated": "2026-10-05 14:45:03", "status": "CANCELLED", "value": 447.30}]""");

        deliver("PAYMENT_REFUNDED", charge, "CONFIRMED", code);
        worker.processPending();

        assertStillPaid(code);
    }

    /** A chargeback ends access as soon as it is opened, whatever step of its dispute the re-read finds it at. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            CHARGEBACK_DISPUTE           | "chargeback": {"status": "IN_DISPUTE"}
            AWAITING_CHARGEBACK_REVERSAL | "chargeback": {"status": "REVERSED"}
            REFUNDED                     | "chargeback": {"status": "DISPUTE_LOST"}""")
    void aChargebackFoundAtAnyStepReversesTheOrder(String status, String chargeback) {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.chargeShows(charge, status, PIX_PRICE_CENTS, code, chargeback);

        deliver("PAYMENT_CHARGEBACK_DISPUTE", charge, status, code);
        worker.processPending();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REVERSED", "enrollment": {"status": "ENDED", "endReason": "CHARGEBACK"}}""");
        assertThat(playback()).hasStatus(HttpStatus.CONFLICT);
    }

    /**
     * After a Reversal the Student sees it and may buy again; and should the owner win the dispute in the Asaas UI,
     * the money coming back grants nothing by itself: an Admin grants the access again by hand.
     */
    @Test
    void afterAChargebackTheStudentMayBuyAgainAndOnlyAnAdminGrantsTheCourseBack() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.chargeShows(charge, "CHARGEBACK_REQUESTED", PIX_PRICE_CENTS, code, "");
        deliver("PAYMENT_CHARGEBACK_REQUESTED", charge, "CHARGEBACK_REQUESTED", code);
        worker.processPending();
        asaas.chargeIs(charge, "CONFIRMED", PIX_PRICE_CENTS, code, false);
        deliver("PAYMENT_CONFIRMED", charge, "CONFIRMED", code);
        worker.processPending();

        assertThat(orders.list()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "status": "REVERSED"}]}""".formatted(code));
        assertThat(playback()).hasStatus(HttpStatus.CONFLICT);
        assertThat(enrollments.list("email=" + studentEmail)).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ENDED", "endReason": "CHARGEBACK"}], "totalItems": 1}""");
        assertThat(orders.placePix(course, null)).hasStatus(HttpStatus.CREATED).bodyJson()
                .extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");

        assertThat(enrollments.grant(studentEmail, course, "Disputa do estorno ganha no Asaas."))
                .hasStatus(HttpStatus.CREATED);
        assertThat(playback()).hasStatusOk();
    }

    /** A refund already going back is not reversed: its Enrollment ended, and its money is on its way. */
    @Test
    void aChargebackOfARefundingOrderLeavesItRefunding() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        assertThat(adminOrders.refund(code)).hasStatusOk();
        asaas.chargeShows(charge, "CHARGEBACK_REQUESTED", PIX_PRICE_CENTS, code, "");

        deliver("PAYMENT_CHARGEBACK_REQUESTED", charge, "CHARGEBACK_REQUESTED", code);
        worker.processPending();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDING", "enrollment": {"status": "ENDED", "endReason": "REFUND"}}""");
    }

    /** A Duplicate payment granted nothing, so its chargeback leaves the Enrollment the other Order granted. */
    @Test
    void aChargebackOfADuplicatePaymentLeavesTheEnrollmentTheOtherOrderGranted() {
        String first = paidOrder();
        String duplicate = new StoredOrders(jdbc).insertAwaitingCopyOf(first);
        asaas.chargeIs(Asaas.chargeOf(duplicate), "CONFIRMED", PIX_PRICE_CENTS, duplicate, false);
        deliver("PAYMENT_CONFIRMED", Asaas.chargeOf(duplicate), "CONFIRMED", duplicate);
        worker.processPending();
        asaas.chargeShows(Asaas.chargeOf(duplicate), "CHARGEBACK_REQUESTED", PIX_PRICE_CENTS, duplicate, "");

        deliver("PAYMENT_CHARGEBACK_REQUESTED", Asaas.chargeOf(duplicate), "CHARGEBACK_REQUESTED", duplicate);
        worker.processPending();

        assertThat(adminOrders.get(duplicate)).bodyJson().isLenientlyEqualTo("""
                {"status": "REVERSED", "duplicatePayment": true}""");
        assertThat(adminOrders.get(first)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "enrollment": {"status": "ACTIVE"}}""");
        assertThat(playback()).hasStatusOk();
    }

    /** A Pix of a CPF account is {@code CONFIRMED} during its cautionary block: it grants access at once all the same. */
    @Test
    void aPixConfirmedUnderACautionaryBlockGrantsAccessAtOnce() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(code), "CONFIRMED", PIX_PRICE_CENTS, code, false);

        deliver("PAYMENT_CONFIRMED", Asaas.chargeOf(code), "CONFIRMED", code);
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
        assertThat(playback()).hasStatusOk();
    }

    /** An upheld block gives the Pix back with no refund of the charge's own. */
    @Test
    void anUpheldCautionaryBlockReversesThePixOrder() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.chargeShows(charge, "REFUNDED", PIX_PRICE_CENTS, code, "");

        deliver("PAYMENT_REFUNDED", charge, "REFUNDED", code);
        worker.processPending();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REVERSED", "enrollment": {"status": "ENDED", "endReason": "PIX_BLOCK_UPHELD"}}""");
        assertThat(playback()).hasStatus(HttpStatus.CONFLICT);
        assertThat(orders.list()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "status": "REVERSED"}]}""".formatted(code));
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).isEmpty();
        assertThat(orders.placePix(course, null)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void aReleasedCautionaryBlockChangesNothing() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, "RECEIVED", PIX_PRICE_CENTS, code, false);

        deliver("PAYMENT_RECEIVED", charge, "RECEIVED", code);
        worker.processPending();
        sendTheStudentsEmails();

        assertStillPaid(code);
        assertThat(mailpit.to(studentEmail)).filteredOn(email -> email.subject().startsWith("Compra confirmada"))
                .hasSize(1);
    }

    /** The Pix the Admin is refunding is no cautionary block, though Asaas lists no refund of it once refunded. */
    @Test
    void aPixTheAdminIsRefundingStaysARefundWhenAsaasListsNoRefundOfIt() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        assertThat(adminOrders.refund(code)).hasStatusOk();
        asaas.chargeShows(charge, "REFUNDED", PIX_PRICE_CENTS, code, "");

        deliver("PAYMENT_REFUNDED", charge, "REFUNDED", code);
        worker.processPending();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDED", "enrollment": {"status": "ENDED", "endReason": "REFUND"}}""");
    }

    /** The webhook of the refund made in the Asaas UI was lost: reconciliation's re-read of the paid Order finds it. */
    @Test
    void reconciliationPicksUpARefundMadeInTheAsaasUiWhoseWebhookWasLost() {
        String code = paidOrder();
        asaas.chargeIsRefunded(Asaas.chargeOf(code), "PENDING", PIX_PRICE_CENTS, code);
        later(paidRecheckInterval);

        reconciliation.reconcile();
        reconciliation.reconcile();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDING", "enrollment": {"status": "ENDED", "endReason": "REFUND"}}""");
        assertThat(playback()).hasStatus(HttpStatus.CONFLICT);
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).containsExactly(studentEmail);
    }

    /** The Admin's refund timed out after Asaas took it, and no webhook came: reconciliation finds the refund. */
    @Test
    void reconciliationPicksUpARefundAsaasTookAfterTheAdminStoppedWaiting() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.answerNextRefundOf(charge, Asaas.tooLate());
        assertThat(adminOrders.refund(code)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        asaas.chargeIsRefunded(charge, "PENDING", PIX_PRICE_CENTS, code);
        later(paidRecheckInterval);

        reconciliation.reconcile();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDING", "enrollment": {"status": "ENDED", "endReason": "REFUND"}}""");
    }

    @Test
    void reconciliationPicksUpAChargebackWhoseWebhookWasLost() {
        String code = paidOrder();
        asaas.chargeShows(Asaas.chargeOf(code), "CHARGEBACK_REQUESTED", PIX_PRICE_CENTS, code, "");
        later(paidRecheckInterval);

        reconciliation.reconcile();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REVERSED", "enrollment": {"status": "ENDED", "endReason": "CHARGEBACK"}}""");
    }

    /** Every paid Order is re-read once per interval, not on every run, which would drain the Asaas quota. */
    @Test
    void reconciliationReReadsAPaidOrderOncePerInterval() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        int readsWhenPaid = asaas.readsOf(charge);

        later(paidRecheckInterval.minusSeconds(1));
        reconciliation.reconcile();
        assertThat(asaas.readsOf(charge)).isEqualTo(readsWhenPaid);

        later(Duration.ofSeconds(1));
        reconciliation.reconcile();
        reconciliation.reconcile();
        assertThat(asaas.readsOf(charge)).isEqualTo(readsWhenPaid + 1);

        later(paidRecheckInterval.minusSeconds(1));
        reconciliation.reconcile();
        assertThat(asaas.readsOf(charge)).isEqualTo(readsWhenPaid + 1);

        later(Duration.ofSeconds(1));
        reconciliation.reconcile();
        assertThat(asaas.readsOf(charge)).isEqualTo(readsWhenPaid + 2);
        assertStillPaid(code);
    }

    /** A refusal is that Order's alone: the next interval re-reads it again. */
    @Test
    void reconciliationReReadsAPaidOrderAgainAfterAsaasRefusedTheReRead() {
        String code = paidOrder();
        String charge = Asaas.chargeOf(code);
        asaas.chargeIsRefunded(charge, "PENDING", PIX_PRICE_CENTS, code);
        asaas.answerNextChargeReadWith(charge, Asaas.error(404, "invalid_payment"));
        later(paidRecheckInterval);

        reconciliation.reconcile();
        assertStillPaid(code);

        later(paidRecheckInterval);
        reconciliation.reconcile();
        assertThat(adminOrders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("REFUNDING");
    }

    /** Money that went back before the Order was ever seen paid was never the API's to give back. */
    @Test
    void aRefundedChargeOfAnOrderNeverSeenPaidPaysNothing() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.chargeIsRefunded(charge, "DONE", PIX_PRICE_CENTS, code);
        String eventId = newEventId();

        assertThat(new AsaasWebhooks(mvc).deliver(paymentEvent(eventId, "PAYMENT_REFUNDED", charge, "REFUNDED",
                PIX_PRICE_CENTS, code))).hasStatusOk();
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
        assertThat(new StoredWebhookEvents(jdbc).statesOf(eventId)).containsExactly("IGNORED");
        assertThat(playback()).hasStatus(HttpStatus.CONFLICT);
    }

    /** The Order is still paid, its Enrollment open, and no refund email queued. */
    private void assertStillPaid(String code) {
        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "enrollment": {"status": "ACTIVE"}}""");
        assertThat(playback()).hasStatusOk();
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).isEmpty();
    }

    /** The Admin's session and its read endpoints, signed in afresh, as the Admin does after a while away. */
    private String signInTheAdmin() {
        String adminToken = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        adminOrders = new AdminOrders(mvc, adminToken);
        enrollments = new AdminEnrollments(mvc, adminToken);
        return adminToken;
    }

    /** Time passes, longer than an Admin's session lasts idle, so the Admin signs in again. */
    private void later(Duration duration) {
        clock.set(clock.instant().plus(duration));
        signInTheAdmin();
    }

    /** A Pix Order, placed by the Student and paid: Asaas confirmed it, its webhook came, and the worker ran. */
    private String paidOrder() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(code), "CONFIRMED", PIX_PRICE_CENTS, code, false);
        deliver("PAYMENT_CONFIRMED", Asaas.chargeOf(code), "CONFIRMED", code);
        worker.processPending();
        assertThat(playback()).hasStatusOk();
        return code;
    }

    /** Asaas delivers an event of the charge, with a new id; what the body claims is only what the event says. */
    private void deliver(String event, String charge, String status, String code) {
        assertThat(new AsaasWebhooks(mvc).deliver(paymentEvent(newEventId(), event, charge, status, PIX_PRICE_CENTS,
                code))).hasStatusOk();
    }

    private MvcTestResult playback() {
        return bff.get("/v1/lessons/%d/playback".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .exchange();
    }

    /** Sends what is queued to the Student, and nothing else. */
    private void sendTheStudentsEmails() {
        new StoredOutboxEmails(jdbc).discardPendingExceptTo(studentEmail);
        outbox.drain();
    }

    private List<Mailpit.Email> refundEmails() {
        return mailpit.to(studentEmail).stream()
                .filter(email -> email.subject().startsWith("Reembolso"))
                .toList();
    }
}
