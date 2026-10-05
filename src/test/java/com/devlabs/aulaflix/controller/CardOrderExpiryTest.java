package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.checkoutEvent;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StoredWebhookEvents;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.OrderExpiry;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * A card Order expires 60 minutes after it was placed, as its Checkout does at Asaas, or when Asaas says the Checkout
 * expired. The Checkout's charges are re-read from Asaas, the WireMock stub, first: a payment wins, and a card held for
 * risk analysis keeps the Order awaiting payment. An expired card Order emails no one.
 */
class CardOrderExpiryTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PRICE_CENTS = 49700;
    private static final Duration CARD_LIFETIME = Duration.ofMinutes(60);

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private OrderExpiry expiry;

    @Autowired
    private WebhookWorker worker;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private String adminEmail;

    private String studentEmail;

    private StudentOrders orders;

    private long course;

    private AsaasWebhooks webhooks;

    @BeforeEach
    void putACourseOnSaleAndSignInAStudent() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        course = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD), storedVideos)
                .onSale(newSlug());
        webhooks = new AsaasWebhooks(mvc);
        BffApi bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(studentEmail, PASSWORD));
    }

    /** A job pays or expires whatever Order of the whole suite is due; what it queued is discarded. */
    @AfterEach
    void discardWhatTheJobsQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void expiresACardOrderWithNothingPaidAfterSixtyMinutesEmailingNothing() {
        String code = orders.placedCard(course);
        clock.set(clock.instant().plus(CARD_LIFETIME));

        expiry.expireDue();
        sendTheStudentsEmails();

        assertThat(orders.get(code)).hasStatusOk().bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("EXPIRED");
            assertThat(order).doesNotHavePath("$.checkout");
        });
        assertThat(asaas.searchesOfCheckout(Asaas.checkoutOf(code))).isOne();
        assertThat(orders.list()).bodyJson().extractingPath("$.items").asArray().isEmpty();
        assertThat(emailsButTheWelcome()).isEmpty();
    }

    /** A card whose capture failed leaves charges Asaas never confirmed: they paid nothing. */
    @Test
    void expiresACardOrderWhoseCheckoutsChargesAreUnpaid() {
        String code = orders.placedCard(course);
        asaas.cardSale(code, "PENDING", 2, PRICE_CENTS, null);
        clock.set(clock.instant().plus(CARD_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
    }

    /** Thirty minutes expire a Pix, not a card. */
    @Test
    void leavesACardOrderAwaitingPaymentUntilItsSixtyMinutesAreUp() {
        String code = orders.placedCard(course);
        clock.set(clock.instant().plus(CARD_LIFETIME).minusSeconds(1));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
        assertThat(asaas.searchesOfCheckout(Asaas.checkoutOf(code))).isZero();
    }

    @Test
    void letsTheStudentPlaceANewCardOrderOnceTheirsExpired() {
        String expired = orders.placedCard(course);
        clock.set(clock.instant().plus(CARD_LIFETIME));
        expiry.expireDue();

        MvcTestResult placed = orders.placeCard(course);

        assertThat(placed).hasStatus(HttpStatus.CREATED);
        String code = StudentOrders.codeOf(placed);
        assertThat(code).isNotEqualTo(expired);
        assertThat(placed).bodyJson().extractingPath("$.checkout.url").isEqualTo(Asaas.checkoutLinkOf(code));
    }

    /** The webhook was lost, or has yet to arrive: the re-read finds the payment, which wins. */
    @Test
    void paysACardOrderWhoseCheckoutAsaasShowsPaidAndGrantsItsEnrollment() {
        String code = orders.placedCard(course);
        asaas.cardSale(code, "CONFIRMED", 10, PRICE_CENTS, null);
        clock.set(clock.instant().plus(CARD_LIFETIME));

        expiry.expireDue();
        sendTheStudentsEmails();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "installments": 10}""");
        assertThat(studentsEnrollments()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "ORDER", "orderCode": "%s"}], "totalItems": 1}"""
                .formatted(code));
        assertThat(emailsButTheWelcome()).singleElement().extracting(Mailpit.Email::subject)
                .asString().startsWith("Compra confirmada");
    }

    @Test
    void leavesACardHeldForRiskAnalysisAwaitingPaymentPastSixtyMinutes() {
        String code = orders.placedCard(course);
        asaas.cardSale(code, "AWAITING_RISK_ANALYSIS", 1, PRICE_CENTS, null);
        clock.set(clock.instant().plus(CARD_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    /** The Student paid as the Order expired: the payment still wins. */
    @Test
    void paysAnExpiredCardOrderWhosePaymentAsaasConfirmsAfterwards() {
        String code = orders.placedCard(course);
        clock.set(clock.instant().plus(CARD_LIFETIME));
        expiry.expireDue();
        String charge = asaas.cardSale(code, "CONFIRMED", 1, PRICE_CENTS, null).getFirst();

        webhooks.deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED", PRICE_CENTS, code));
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "installments": 1}""");
        assertThat(studentsEnrollments()).bodyJson().extractingPath("$.totalItems").isEqualTo(1);
    }

    @Test
    void leavesTheOrderAwaitingPaymentWhileAsaasCannotBeReachedAndExpiresItOnTheNextRun() {
        String code = orders.placedCard(course);
        asaas.answerNextChargeSearchOfCheckout(Asaas.checkoutOf(code), Asaas.tooLate());
        clock.set(clock.instant().plus(CARD_LIFETIME));

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");

        expiry.expireDue();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
    }

    @Test
    void expiresACardOrderWhenAsaasSaysItsCheckoutExpired() {
        String code = orders.placedCard(course);
        String eventId = newEventId();

        MvcTestResult delivered = webhooks.deliver(checkoutEvent(eventId, "CHECKOUT_EXPIRED",
                Asaas.checkoutOf(code), "EXPIRED"));
        worker.processPending();

        assertThat(delivered).hasStatusOk();
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
        assertThat(new StoredWebhookEvents(jdbc).statesOf(eventId)).containsExactly("PROCESSED");
    }

    @Test
    void paysACardOrderWhoseCheckoutExpiredAfterItsPaymentAndGrantsItsEnrollment() {
        String code = orders.placedCard(course);
        asaas.cardSale(code, "CONFIRMED", 2, PRICE_CENTS, null);

        webhooks.deliver(checkoutEvent(newEventId(), "CHECKOUT_EXPIRED", Asaas.checkoutOf(code), "EXPIRED"));
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "installments": 2}""");
        assertThat(studentsEnrollments()).bodyJson().extractingPath("$.totalItems").isEqualTo(1);
    }

    @Test
    void keepsACardHeldForRiskAnalysisAwaitingPaymentWhenItsCheckoutExpires() {
        String code = orders.placedCard(course);
        asaas.cardSale(code, "AWAITING_RISK_ANALYSIS", 1, PRICE_CENTS, null);

        webhooks.deliver(checkoutEvent(newEventId(), "CHECKOUT_EXPIRED", Asaas.checkoutOf(code), "EXPIRED"));
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void ignoresTheExpiryOfACheckoutNoOrderAwaitsPaymentOn() {
        String unknown = newEventId();
        String paidCode = orders.placedCard(course);
        asaas.cardSale(paidCode, "CONFIRMED", 1, PRICE_CENTS, null);
        clock.set(clock.instant().plus(CARD_LIFETIME));
        expiry.expireDue();
        String afterPayment = newEventId();

        webhooks.deliver(checkoutEvent(unknown, "CHECKOUT_EXPIRED", "chk_" + UUID.randomUUID(), "EXPIRED"));
        webhooks.deliver(checkoutEvent(afterPayment, "CHECKOUT_EXPIRED", Asaas.checkoutOf(paidCode), "EXPIRED"));
        worker.processPending();

        StoredWebhookEvents stored = new StoredWebhookEvents(jdbc);
        assertThat(stored.statesOf(unknown)).containsExactly("IGNORED");
        assertThat(stored.statesOf(afterPayment)).containsExactly("IGNORED");
        assertThat(orders.get(paidCode)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
    }

    @Test
    void storesACheckoutEventWithoutItsCheckoutAsUnprocessable() {
        String eventId = newEventId();

        assertThat(webhooks.deliver("""
                {"id": "%s", "event": "CHECKOUT_EXPIRED", "checkout": {"status": "EXPIRED"}}""".formatted(eventId)))
                .hasStatusOk();

        assertThat(new StoredWebhookEvents(jdbc).statesOf(eventId)).containsExactly("UNPROCESSABLE");
    }

    /** A Checkout's id is kept in 64 characters, as a charge's is. */
    @Test
    void takesACheckoutIdOfUpTo64Characters() {
        String longest = newEventId();
        String tooLong = newEventId();

        webhooks.deliver(checkoutEvent(longest, "CHECKOUT_EXPIRED", "c".repeat(64), "EXPIRED"));
        webhooks.deliver(checkoutEvent(tooLong, "CHECKOUT_EXPIRED", "c".repeat(65), "EXPIRED"));

        StoredWebhookEvents stored = new StoredWebhookEvents(jdbc);
        assertThat(stored.statesOf(longest)).containsExactly("PENDING");
        assertThat(stored.statesOf(tooLong)).containsExactly("UNPROCESSABLE");
        worker.processPending();
        assertThat(stored.statesOf(longest)).containsExactly("IGNORED");
    }

    /** Only an expiry is acted on here; a Checkout's other events are stored as ignored. */
    @Test
    void ignoresTheCheckoutsOtherEvents() {
        String code = orders.placedCard(course);
        String paid = newEventId();

        webhooks.deliver(checkoutEvent(paid, "CHECKOUT_PAID", Asaas.checkoutOf(code), "PAID"));

        assertThat(new StoredWebhookEvents(jdbc).statesOf(paid)).containsExactly("IGNORED");
    }

    /** Sends what is queued to the Student, and nothing else, which other tests' jobs may have queued. */
    private void sendTheStudentsEmails() {
        new StoredOutboxEmails(jdbc).discardPendingExceptTo(studentEmail);
        outbox.drain();
    }

    private List<Mailpit.Email> emailsButTheWelcome() {
        return mailpit.to(studentEmail).stream()
                .filter(email -> !email.subject().startsWith("Boas-vindas"))
                .toList();
    }

    private MvcTestResult studentsEnrollments() {
        return new AdminEnrollments(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD))
                .list("email=" + studentEmail);
    }
}
