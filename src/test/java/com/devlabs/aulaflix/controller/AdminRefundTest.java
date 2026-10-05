package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredOrders;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.OrderReconciliation;
import com.devlabs.aulaflix.service.WebhookWorker;
import com.jayway.jsonpath.JsonPath;

/**
 * The Admin refunds an Order in one call, as the 7-day guarantee promises: Asaas, the WireMock stub, takes the refund,
 * the Order becomes {@code REFUNDING}, the Enrollment it granted ends, and the Student is emailed; reconciliation
 * follows the refund until Asaas reports it {@code DONE}.
 */
class AdminRefundTest extends IntegrationTest {

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

    private String adminEmail;

    private AdminCourses courses;

    private AdminOrders adminOrders;

    private AdminEnrollments enrollments;

    private BffApi bff;

    private String studentEmail;

    private String studentToken;

    private StudentOrders orders;

    @BeforeEach
    void signInAnAdminAndAStudent() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        adminOrders = new AdminOrders(mvc, adminToken);
        enrollments = new AdminEnrollments(mvc, adminToken);
        bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        studentToken = new StudentApi(bff).signedUp(studentEmail, PASSWORD);
        orders = new StudentOrders(bff, studentToken);
    }

    /** Reconciliation follows every Order of the whole suite: what it queued is discarded. */
    @AfterEach
    void discardWhatTheJobsQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void anAcceptedRefundEndsTheEnrollmentTheOrderGrantedAndEmailsTheStudent() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String code = paidOrder(course);
        assertThat(playback(lesson)).hasStatusOk();
        Instant requestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long adminId = new StoredAccounts(jdbc).find(adminEmail).orElseThrow().id();

        MvcTestResult refund = adminOrders.refund(code);
        sendTheStudentsEmails();

        assertThat(refund).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "REFUNDING", "refundRequestedAt": "%s",
                 "refundRequestedBy": {"id": %d, "email": "%s", "name": "Ana"},
                 "enrollment": {"status": "ENDED", "endedAt": "%s", "endReason": "REFUND"}}"""
                .formatted(code, requestedAt, adminId, adminEmail, requestedAt));
        assertThat(refund).bodyJson().doesNotHavePath("$.refundedAt");
        assertThat(asaas.refundsOf(Asaas.chargeOf(code))).isOne();
        assertThat(playback(lesson)).hasStatus(HttpStatus.CONFLICT);
        assertThat(enrollments.list("email=" + studentEmail)).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ENDED", "origin": "ORDER", "orderCode": "%s", "endReason": "REFUND"}],
                 "totalItems": 1}""".formatted(code));
        assertThat(orders.list()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "status": "REFUNDING"}]}""".formatted(code));
        assertThat(refundEmails()).singleElement().satisfies(email -> {
            assertThat(email.to()).containsExactly(studentEmail);
            assertThat(email.subject()).isEqualTo("Reembolso do pedido " + code);
            assertThat(email.text()).contains("pedido " + code, "R$ 447,30", COURSE_TITLE);
        });
    }

    /** Ending by hand is for manual Enrollments only: a paid one gets 409 even once its refund ended it. */
    @Test
    void refusesAnAdminWhoEndsAPaidEnrollmentTheRefundEndedAlready() {
        long course = courses.onSale(newSlug());
        String code = paidOrder(course);
        adminOrders.refund(code);
        long enrollment = ((Number) JsonPath.read(AdminApi.body(enrollments.list("email=" + studentEmail)),
                "$.items[0].id")).longValue();
        String refunded = AdminApi.body(enrollments.get(Long.toString(enrollment)));

        MvcTestResult ended = enrollments.end(enrollment, "Encerrar de novo.");

        assertThat(ended).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/paid-enrollment");
        assertThat(enrollments.get(Long.toString(enrollment))).bodyJson().isStrictlyEqualTo(refunded);
    }

    /** The refund is full: Asaas is asked for no value, which refunds all of it. */
    @Test
    void asksAsaasForAFullRefund() {
        String code = paidOrder(courses.onSale(newSlug()));

        adminOrders.refund(code);

        assertThat(asaas.refundRequestsOf(Asaas.chargeOf(code))).containsExactly("{}");
    }

    @Test
    void reconciliationMakesTheOrderRefundedOnceAsaasReportsTheRefundDone() {
        String code = paidOrder(courses.onSale(newSlug()));
        adminOrders.refund(code);
        asaas.chargeIsRefunded(Asaas.chargeOf(code), "PENDING", PIX_PRICE_CENTS, code);

        reconciliation.reconcile();

        assertThat(adminOrders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("REFUNDING");

        clock.set(clock.instant().plus(Duration.ofMinutes(10)));
        Instant refundedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        asaas.chargeIsRefunded(Asaas.chargeOf(code), "DONE", PIX_PRICE_CENTS, code);

        reconciliation.reconcile();
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));
        reconciliation.reconcile();

        assertThat(adminOrders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDED", "refundedAt": "%s"}""".formatted(refundedAt));
        assertThat(orders.list()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "status": "REFUNDED"}]}""".formatted(code));
    }

    /** A refusal is that Order's alone, and its refund is read again on the next run. */
    @Test
    void reconciliationFollowsARefundAgainAfterAsaasRefusedToReadItsCharge() {
        String code = paidOrder(courses.onSale(newSlug()));
        adminOrders.refund(code);
        asaas.chargeIsRefunded(Asaas.chargeOf(code), "DONE", PIX_PRICE_CENTS, code);
        asaas.answerNextChargeReadWith(Asaas.chargeOf(code), Asaas.error(404, "invalid_payment"));

        reconciliation.reconcile();

        assertThat(adminOrders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("REFUNDING");

        reconciliation.reconcile();

        assertThat(adminOrders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("REFUNDED");
    }

    @Test
    void aRefusedRefundAnswersAsaasReasonsAndChangesNothing() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String code = paidOrder(course);
        asaas.answerNextRefundOf(Asaas.chargeOf(code), Asaas.error(400, "invalid_action",
                "Saldo insuficiente para realizar o estorno."));

        MvcTestResult refund = adminOrders.refund(code);

        assertThat(refund).hasStatus(HttpStatus.CONFLICT).bodyJson().isLenientlyEqualTo("""
                {"type": "https://aulaflix.com.br/problems/refund-refused", "status": 409,
                 "reasons": [{"code": "invalid_action",
                              "description": "Saldo insuficiente para realizar o estorno."}]}""");
        assertNothingChanged(code, lesson);
    }

    @Test
    void aRefundAsaasAnswersUnreadablyIsRefusedWithoutReasons() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String code = paidOrder(course);
        asaas.answerNextRefundOf(Asaas.chargeOf(code), aResponse().withStatus(403).withBody("<html>Forbidden"));

        MvcTestResult refund = adminOrders.refund(code);

        assertThat(refund).hasStatus(HttpStatus.CONFLICT).bodyJson().isLenientlyEqualTo("""
                {"type": "https://aulaflix.com.br/problems/refund-refused", "reasons": []}""");
        assertNothingChanged(code, lesson);
    }

    @ParameterizedTest
    @ValueSource(strings = {"timeout", "server error", "too many requests"})
    void aRefundWhileAsaasCannotBeReachedAnswers503AndChangesNothing(String failure) {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String code = paidOrder(course);
        asaas.answerNextRefundOf(Asaas.chargeOf(code), switch (failure) {
            case "timeout" -> Asaas.tooLate();
            case "server error" -> serverError();
            default -> aResponse().withStatus(429);
        });

        MvcTestResult refund = adminOrders.refund(code);

        assertThat(refund).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).containsHeader(HttpHeaders.RETRY_AFTER)
                .bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/payment-unavailable");
        assertNothingChanged(code, lesson);
    }

    @Test
    void aRepeatedRefundAnswersTheOrderWithoutAskingAsaasAgain() {
        String code = paidOrder(courses.onSale(newSlug()));
        MvcTestResult first = adminOrders.refund(code);
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));

        MvcTestResult again = adminOrders.refund(code);

        assertThat(again).hasStatusOk().bodyJson().isStrictlyEqualTo(AdminApi.body(first));
        assertThat(asaas.refundsOf(Asaas.chargeOf(code))).isOne();
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).containsExactly(studentEmail);
    }

    @Test
    void aRefundOfARefundedOrderAnswersItWithoutAskingAsaas() {
        String code = paidOrder(courses.onSale(newSlug()));
        adminOrders.refund(code);
        asaas.chargeIsRefunded(Asaas.chargeOf(code), "DONE", PIX_PRICE_CENTS, code);
        reconciliation.reconcile();

        MvcTestResult again = adminOrders.refund(code);

        assertThat(again).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("REFUNDED");
        assertThat(asaas.refundsOf(Asaas.chargeOf(code))).isOne();
    }

    /** The Duplicate payment granted nothing: the Enrollment the other Order granted stays, and the email says so. */
    @Test
    void refundingADuplicatePaymentLeavesTheEnrollmentTheOtherOrderGranted() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String first = paidOrder(course);
        String duplicate = new StoredOrders(jdbc).insertAwaitingCopyOf(first);
        confirm(duplicate);

        MvcTestResult refund = adminOrders.refund(duplicate);
        sendTheStudentsEmails();

        assertThat(refund).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"status": "REFUNDING", "duplicatePayment": true}""");
        assertThat(refund).bodyJson().doesNotHavePath("$.enrollment");
        assertThat(playback(lesson)).hasStatusOk();
        assertThat(enrollments.list("email=" + studentEmail)).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "ORDER", "orderCode": "%s"}], "totalItems": 1}"""
                .formatted(first));
        assertThat(adminOrders.get(first)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "enrollment": {"status": "ACTIVE"}}""");
        assertThat(refundEmails()).singleElement().satisfies(email ->
                assertThat(email.text()).contains("acesso ao curso continua liberado")
                        .doesNotContain("acesso ao curso foi encerrado"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AWAITING_PAYMENT", "EXPIRED", "CANCELLED"})
    void refundingAnOrderThatWasNeverPaidIsRefused(String status) {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        jdbc.update("update orders set status = ? where code = ?", status, code);

        MvcTestResult refund = adminOrders.refund(code);

        assertThat(refund).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/order-not-paid");
        assertThat(asaas.refundsOf(Asaas.chargeOf(code))).isZero();
        assertThat(adminOrders.get(code)).bodyJson().extractingPath("$.status").isEqualTo(status);
    }

    @Test
    void refundingAnUnknownOrderIsNotFound() {
        assertThat(adminOrders.refund("ZZZZZZZZ")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/order-not-found");
    }

    @Test
    void theStudentCanBuyTheCourseAgainAfterARefund() {
        long course = courses.onSale(newSlug());
        String refunded = paidOrder(course);
        adminOrders.refund(refunded);

        MvcTestResult again = orders.placePix(course, null);

        assertThat(again).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.status")
                .isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void aStudentCannotRefund() {
        String code = paidOrder(courses.onSale(newSlug()));

        assertThat(new AdminOrders(mvc, studentToken).refund(code)).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(asaas.refundsOf(Asaas.chargeOf(code))).isZero();
    }

    /** The Order is still paid, with no refund, its Enrollment open, and no email queued. */
    private void assertNothingChanged(String code, long lesson) {
        MvcTestResult order = adminOrders.get(code);
        assertThat(order).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "enrollment": {"status": "ACTIVE"}}""");
        assertThat(order).bodyJson().doesNotHavePath("$.refundRequestedAt");
        assertThat(order).bodyJson().doesNotHavePath("$.refundRequestedBy");
        assertThat(playback(lesson)).hasStatusOk();
        assertThat(new StoredOutboxEmails(jdbc).recipientsOf("REFUND_NOTICE", code)).isEmpty();
    }

    /** A Pix Order of the Course, placed by the Student and paid: its webhook came, and the worker ran. */
    private String paidOrder(long course) {
        String code = orders.placedPix(course, Cpfs.newCpf());
        confirm(code);
        return code;
    }

    /** Asaas confirms the charge, then its webhook is delivered and the worker runs. */
    private void confirm(String code) {
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, "CONFIRMED", PIX_PRICE_CENTS, code, false);
        assertThat(new AsaasWebhooks(mvc).deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge,
                "CONFIRMED", PIX_PRICE_CENTS, code))).hasStatusOk();
        worker.processPending();
    }

    private long paidLessonOf(long course) {
        return courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"), "Rotas no Express",
                "rotas-no-express", "five-seconds.mp4");
    }

    private MvcTestResult playback(long lesson) {
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
