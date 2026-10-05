package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
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
 * A card paid on the Checkout pays its Order through the same webhook path as a Pix: the worker re-reads the charge
 * from Asaas, the WireMock stub, and acts on that. A card sale in installments is one charge per installment, each of
 * which Asaas may confirm; the Order is paid, with how many installments the Student chose, and grants one Enrollment.
 * Asaas's manual risk analysis holds a card payment, and its rejection declines the Order.
 */
class CardPaymentTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PRICE_CENTS = 49700;

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

    private AsaasWebhooks webhooks;

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
        AdminCourses courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD),
                storedVideos);
        course = courses.onSale(newSlug());
        lesson = courses.addPublishedLesson(courses.addModule(course, "Rotas"), "Primeira rota", "primeira-rota",
                "five-seconds.mp4");
        webhooks = new AsaasWebhooks(mvc);
        bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        studentToken = new StudentApi(bff).signedUp(studentEmail, PASSWORD);
        orders = new StudentOrders(bff, studentToken);
    }

    /** Reconciliation pays whatever Order of the whole suite is due; what it queued is discarded. */
    @AfterEach
    void discardWhatTheJobsQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    /**
     * Whether Asaas copies the Checkout's external reference onto its charges is unverified: the charge's Checkout
     * names the Order either way.
     */
    @ParameterizedTest(name = "external reference on the charge: {0}")
    @ValueSource(booleans = {false, true})
    void aConfirmedCardPaymentPaysTheOrderInOneInstallmentAndGrantsTheEnrollment(boolean referenced) {
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "CONFIRMED", 1, PRICE_CENTS, referenced ? code : null).getFirst();

        MvcTestResult delivered = deliver("PAYMENT_CONFIRMED", charge, code);
        worker.processPending();

        assertThat(delivered).hasStatusOk();
        assertThat(orders.get(code)).hasStatusOk().bodyJson().satisfies(order -> {
            assertThat(order).isLenientlyEqualTo("""
                    {"code": "%s", "status": "PAID", "method": "CARD", "amountCents": %d, "paidAt": "%s",
                     "installments": 1, "duplicatePayment": false}"""
                    .formatted(code, PRICE_CENTS, clock.instant().truncatedTo(ChronoUnit.MICROS)));
            assertThat(order).doesNotHavePath("$.checkout");
        });
        assertThat(studentsEnrollments()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "ORDER", "orderCode": "%s", "course": {"id": %d}}],
                 "totalItems": 1}""".formatted(code, course));
        assertThat(playback()).hasStatusOk();
    }

    /** Each installment is its own charge; however many of them Asaas confirms, the sale is one. */
    @Test
    void aTenInstallmentSaleGrantsOneEnrollmentAndSendsOneEmailHoweverManyConfirmationsAsaasSends() {
        String code = orders.placedCard(course);
        List<String> charges = asaas.cardSale(code, "CONFIRMED", 10, PRICE_CENTS, null);

        charges.forEach(charge -> assertThat(deliver("PAYMENT_CONFIRMED", charge, code)).hasStatusOk());
        worker.processPending();
        deliver("PAYMENT_CONFIRMED", charges.get(3), code);
        worker.processPending();
        sendTheStudentsEmails();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "installments": 10, "amountCents": %d}""".formatted(PRICE_CENTS));
        assertThat(studentsEnrollments()).bodyJson().extractingPath("$.totalItems").isEqualTo(1);
        assertThat(purchaseEmails()).singleElement().extracting(Mailpit.Email::text).asString()
                .contains("pedido " + code, "R$ 497,00");
    }

    /** A card sale in three installments of a price three does not divide: Asaas's plan holds the whole. */
    @Test
    void takesTheInstallmentPlansTotalAsTheAmountPaid() {
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "CONFIRMED", 3, PRICE_CENTS, null).getLast();

        deliver("PAYMENT_CONFIRMED", charge, code);
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "installments": 3}""");
    }

    @Test
    void grantsNothingWhenTheInstallmentPlanIsForAnotherAmount() {
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "CONFIRMED", 2, PRICE_CENTS, null).getFirst();
        asaas.installmentIs(Asaas.installmentOf(code), PRICE_CENTS - 2, 2, Asaas.checkoutOf(code));
        String eventId = newEventId();

        webhooks.deliver(paymentEvent(eventId, "PAYMENT_CONFIRMED", charge, "CONFIRMED", PRICE_CENTS / 2, code));
        worker.processPending();

        assertThat(new StoredWebhookEvents(jdbc).statesOf(eventId)).containsExactly("UNPROCESSABLE");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
        assertThat(studentsEnrollments()).bodyJson().extractingPath("$.totalItems").isEqualTo(0);
    }

    @Test
    void grantsNothingWhenTheChargeIsUnderAnotherReference() {
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "CONFIRMED", 1, PRICE_CENTS, "K7M2Q9XA").getFirst();

        deliver("PAYMENT_CONFIRMED", charge, code);
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    /** Only a card's charge may lack the reference, and then only on the Order's Checkout. */
    @Test
    void grantsNothingForAPixChargeUnderNoReference() {
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.answerChargeReadsWith(charge, okJson("""
                {"object": "payment", "id": "%s", "billingType": "PIX", "status": "CONFIRMED", "value": 447.30,
                 "externalReference": null, "deleted": false}""".formatted(charge)));
        String eventId = newEventId();

        webhooks.deliver(paymentEvent(eventId, "PAYMENT_CONFIRMED", charge, "CONFIRMED", 44730, code));
        worker.processPending();

        assertThat(new StoredWebhookEvents(jdbc).statesOf(eventId)).containsExactly("UNPROCESSABLE");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    /** A card held for Asaas's manual risk analysis is neither paid nor declined yet. */
    @Test
    void leavesTheOrderAwaitingPaymentWhileRiskAnalysisIsPending() {
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "AWAITING_RISK_ANALYSIS", 1, PRICE_CENTS, null).getFirst();

        assertThat(deliver("PAYMENT_AWAITING_RISK_ANALYSIS", charge, code)).hasStatusOk();
        assertThat(deliver("PAYMENT_CONFIRMED", charge, code)).hasStatusOk();
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
            assertThat(order).extractingPath("$.checkout.url").isEqualTo(Asaas.checkoutLinkOf(code));
        });
        assertThat(studentsEnrollments()).bodyJson().extractingPath("$.totalItems").isEqualTo(0);
    }

    @Test
    void paysTheOrderOnceRiskAnalysisApprovesTheCard() {
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "AWAITING_RISK_ANALYSIS", 1, PRICE_CENTS, null).getFirst();
        deliver("PAYMENT_AWAITING_RISK_ANALYSIS", charge, code);
        worker.processPending();
        asaas.cardSale(code, "CONFIRMED", 1, PRICE_CENTS, null);

        deliver("PAYMENT_APPROVED_BY_RISK_ANALYSIS", charge, code);
        deliver("PAYMENT_CONFIRMED", charge, code);
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
        assertThat(studentsEnrollments()).bodyJson().extractingPath("$.totalItems").isEqualTo(1);
    }

    /**
     * What the charge's status is once risk analysis rejects the card is unverified; it is no longer paid nor held
     * for analysis, which the re-read checks before declining the Order.
     */
    @Test
    void declinesTheOrderWhenRiskAnalysisRejectsTheCardGrantingNothingAndLetsTheStudentPlaceANewOne() {
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "PENDING", 1, PRICE_CENTS, null).getFirst();
        String eventId = newEventId();

        assertThat(webhooks.deliver(paymentEvent(eventId, "PAYMENT_REPROVED_BY_RISK_ANALYSIS", charge, "PENDING",
                PRICE_CENTS, code))).hasStatusOk();
        worker.processPending();
        sendTheStudentsEmails();

        assertThat(new StoredWebhookEvents(jdbc).statesOf(eventId)).containsExactly("PROCESSED");
        assertThat(orders.get(code)).hasStatusOk().bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("DECLINED");
            assertThat(order).doesNotHavePath("$.checkout");
        });
        assertThat(orders.list()).bodyJson().extractingPath("$.items").asArray().isEmpty();
        assertThat(studentsEnrollments()).bodyJson().extractingPath("$.totalItems").isEqualTo(0);
        assertThat(purchaseEmails()).isEmpty();
        MvcTestResult again = orders.placeCard(course);
        assertThat(again).hasStatus(HttpStatus.CREATED);
        assertThat(StudentOrders.codeOf(again)).isNotEqualTo(code);
    }

    /** Anyone holding the token could send a rejection: the re-read decides. */
    @Test
    void declinesNothingTheReReadShowsPaidOrStillHeldForAnalysis() {
        String paid = orders.placedCard(course);
        String paidCharge = asaas.cardSale(paid, "CONFIRMED", 1, PRICE_CENTS, null).getFirst();

        deliver("PAYMENT_REPROVED_BY_RISK_ANALYSIS", paidCharge, paid);
        worker.processPending();

        assertThat(orders.get(paid)).bodyJson().extractingPath("$.status").isEqualTo("PAID");

        AdminCourses courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD),
                storedVideos);
        long another = courses.onSale(newSlug());
        String held = orders.placedCard(another);
        String heldCharge = asaas.cardSale(held, "AWAITING_RISK_ANALYSIS", 1, PRICE_CENTS, null).getFirst();

        deliver("PAYMENT_REPROVED_BY_RISK_ANALYSIS", heldCharge, held);
        worker.processPending();

        assertThat(orders.get(held)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void ignoresAChargeOfACheckoutNoOrderHas() {
        String eventId = newEventId();
        String code = "K7M2Q9XA";
        String charge = asaas.cardSale("NOORDER" + UUID.randomUUID(), "CONFIRMED", 1, PRICE_CENTS, null)
                .getFirst();

        webhooks.deliver(paymentEvent(eventId, "PAYMENT_CONFIRMED", charge, "CONFIRMED", PRICE_CENTS, code));
        worker.processPending();

        assertThat(new StoredWebhookEvents(jdbc).statesOf(eventId)).containsExactly("IGNORED");
    }

    /** The webhook was lost: reconciliation finds the payment by the Order's Checkout. */
    @Test
    void reconciliationPaysACardOrderWhoseWebhookWasLost() {
        String code = orders.placedCard(course);
        asaas.cardSale(code, "CONFIRMED", 10, PRICE_CENTS, null);
        clock.set(clock.instant().plus(Duration.ofMinutes(6)));

        reconciliation.reconcile();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "installments": 10}""");
        assertThat(studentsEnrollments()).bodyJson().extractingPath("$.totalItems").isEqualTo(1);
    }

    @Test
    void reconciliationLeavesACardOrderWithNothingPaidAwaitingPayment() {
        String code = orders.placedCard(course);
        clock.set(clock.instant().plus(Duration.ofMinutes(6)));

        reconciliation.reconcile();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
        assertThat(asaas.searchesOfCheckout(Asaas.checkoutOf(code))).isOne();
    }

    private MvcTestResult deliver(String event, String charge, String code) {
        return webhooks.deliver(paymentEvent(newEventId(), event, charge, "CONFIRMED", PRICE_CENTS, code));
    }

    /** Sends what is queued to the Student, and nothing else, which other tests' jobs may have queued. */
    private void sendTheStudentsEmails() {
        new StoredOutboxEmails(jdbc).discardPendingExceptTo(studentEmail);
        outbox.drain();
    }

    private List<Mailpit.Email> purchaseEmails() {
        return mailpit.to(studentEmail).stream()
                .filter(email -> email.subject().startsWith("Compra confirmada"))
                .toList();
    }

    private MvcTestResult studentsEnrollments() {
        return new AdminEnrollments(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD))
                .list("email=" + studentEmail);
    }

    private MvcTestResult playback() {
        return bff.get("/v1/lessons/%d/playback".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .exchange();
    }
}
