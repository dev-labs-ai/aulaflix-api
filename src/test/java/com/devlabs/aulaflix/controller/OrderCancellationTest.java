package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredOrders;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.OrderExpiry;
import com.devlabs.aulaflix.service.OrderReconciliation;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * A Student gives up an Order awaiting payment, on AulaFlix's page or by cancelling on Asaas's, which sends them back to
 * the payment form: the Pix charge is deleted, or the Checkout cancelled, at Asaas, and only then is the Order
 * {@code CANCELLED}, so that nothing left at Asaas can be paid for it. Asaas is the WireMock stub, so what it was sent is
 * part of the contract.
 */
class OrderCancellationTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PRICE_CENTS = 49700;
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
    private OrderExpiry expiry;

    @Autowired
    private OrderReconciliation reconciliation;

    @Value("${aulaflix.asaas.reconciliation-delay}")
    private Duration reconciliationDelay;

    @Autowired
    private JdbcTemplate jdbc;

    private AsaasWebhooks webhooks;

    private AdminCourses courses;

    private StudentOrders orders;

    @BeforeEach
    void signInAnAdminAndAStudent() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos);
        webhooks = new AsaasWebhooks(mvc);
        BffApi bff = new BffApi(mvc);
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));
    }

    /** A payment emails its Student, and the jobs pay or expire whatever Order of the suite is due: none is sent. */
    @AfterEach
    void discardWhatWasQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void cancelsTheCheckoutAtAsaasThenTheCardOrderAndAnswersItAgainWithoutAsaas() {
        String code = orders.placedCard(courses.onSale(newSlug()));

        MvcTestResult cancelled = orders.cancel(code);

        assertThat(cancelled).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON).bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.code").isEqualTo(code);
            assertThat(order).extractingPath("$.status").isEqualTo("CANCELLED");
            assertThat(order).doesNotHavePath("$.checkout");
        });
        assertThat(asaas.cancellationsOf(Asaas.checkoutOf(code))).isEqualTo(1);
        assertThat(orders.cancel(code)).hasStatusOk().bodyJson().isStrictlyEqualTo(AdminApi.body(cancelled));
        assertThat(asaas.cancellationsOf(Asaas.checkoutOf(code))).isEqualTo(1);
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void deletesThePixChargeAtAsaasThenCancelsTheOrder() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());

        MvcTestResult cancelled = orders.cancel(code);

        assertThat(cancelled).hasStatusOk().bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("CANCELLED");
            assertThat(order).doesNotHavePath("$.pix");
        });
        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isEqualTo(1);
        assertThat(orders.cancel(code)).hasStatusOk();
        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isEqualTo(1);
        assertThat(orders.list()).bodyJson().extractingPath("$.items").asArray().isEmpty();
    }

    /**
     * The API stopped while placing it, before its charge's id came back: the charge Asaas may have made all the same
     * is found under the Order's code, and deleted, by reconciliation.
     */
    @Test
    void leavesTheChargesOfAPixWhoseIdNeverCameBackToReconciliation() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        new StoredOrders(jdbc).forgetCharge(code);

        assertThat(orders.cancel(code)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isZero();

        clock.set(clock.instant().plus(reconciliationDelay));
        reconciliation.reconcile();

        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isOne();
    }

    @Test
    void refusesToCancelAPaidOrder() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(code), "RECEIVED", PIX_PRICE_CENTS, code, false);
        webhooks.deliver(AsaasWebhooks.paymentEvent(AsaasWebhooks.newEventId(), "PAYMENT_RECEIVED",
                Asaas.chargeOf(code), "RECEIVED", PIX_PRICE_CENTS, code));
        worker.processPending();

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/order-not-awaiting-payment");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isZero();
    }

    @Test
    void refusesToCancelAnExpiredOrder() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(code), "PENDING", PIX_PRICE_CENTS, code, false);
        clock.set(clock.instant().plus(Duration.ofMinutes(30)));
        expiry.expireDue();

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/order-not-awaiting-payment");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("EXPIRED");
    }

    @Test
    void answersAnotherStudentsCodeLikeAnUnknownOne() {
        String code = orders.placedCard(courses.onSale(newSlug()));
        BffApi bff = new BffApi(mvc);
        StudentOrders another = new StudentOrders(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));

        for (MvcTestResult refused : List.of(another.cancel(code), orders.cancel("ZZZZZZZZ"))) {
            assertThat(refused).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.type")
                    .isEqualTo("https://aulaflix.com.br/problems/order-not-found");
        }
        assertThat(asaas.cancellationsOf(Asaas.checkoutOf(code))).isZero();
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void leavesTheOrderAwaitingWhenAsaasCannotCancelTheCheckout() {
        String code = orders.placedCard(courses.onSale(newSlug()));
        asaas.answerNextCancellationOf(Asaas.checkoutOf(code), serverError());

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, "30")
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/payment-unavailable");
        assertThat(orders.get(code)).bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
            assertThat(order).extractingPath("$.checkout.url").isEqualTo(Asaas.checkoutLinkOf(code));
        });
        assertThat(orders.cancel(code)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void leavesTheOrderAwaitingWhenAsaasTakesTooLongToDeleteThePixCharge() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        asaas.answerNextDeletionOf(Asaas.chargeOf(code), Asaas.tooLate());

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(orders.get(code)).bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
            assertThat(order).extractingPath("$.pix.copyPasteCode")
                    .isEqualTo(Asaas.copyPasteCodeOf(Asaas.chargeOf(code)));
        });
    }

    /** Asaas keeps the charge payable, so the Order must stay payable too. */
    @Test
    void leavesTheOrderAwaitingWhenAsaasRefusesToDeleteAChargeStillPending() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(code), "PENDING", PIX_PRICE_CENTS, code, false);
        asaas.answerNextDeletionOf(Asaas.chargeOf(code), Asaas.error(400, "invalid_action"));

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.BAD_GATEWAY)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/payment-provider-error");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    /** The Student paid, then gave up before the webhook came: Asaas will not delete a paid charge, and it wins. */
    @Test
    void paysTheOrderWhenAsaasRefusesToDeleteAChargeThatWasPaid() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(code), "RECEIVED", PIX_PRICE_CENTS, code, false);
        asaas.answerNextDeletionOf(Asaas.chargeOf(code), Asaas.error(400, "invalid_action"));

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/order-not-awaiting-payment");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
    }

    @Test
    void cancelsTheOrderWhenAsaasRefusesToDeleteAChargeAlreadyDeleted() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(code), "PENDING", PIX_PRICE_CENTS, code, true);
        asaas.answerNextDeletionOf(Asaas.chargeOf(code), Asaas.error(404, "not_found"));

        assertThat(orders.cancel(code)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    }

    /**
     * Cancelling on Asaas's page may have cancelled the Checkout there already: Asaas refuses to cancel it again, and
     * no charge was made on it, so nothing is left to pay.
     */
    @Test
    void cancelsTheOrderWhenAsaasRefusesToCancelACheckoutNobodyPaidOn() {
        String code = orders.placedCard(courses.onSale(newSlug()));
        asaas.answerNextCancellationOf(Asaas.checkoutOf(code), Asaas.error(400, "invalid_status"));

        assertThat(orders.cancel(code)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
        assertThat(asaas.searchesOfCheckout(Asaas.checkoutOf(code))).isOne();
    }

    @Test
    void paysTheOrderWhenAsaasRefusesToCancelACheckoutThatWasPaid() {
        String code = orders.placedCard(courses.onSale(newSlug()));
        asaas.cardSale(code, "CONFIRMED", 3, PRICE_CENTS, code);
        asaas.answerNextCancellationOf(Asaas.checkoutOf(code), Asaas.error(400, "invalid_status"));

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/order-not-awaiting-payment");
        assertThat(orders.get(code)).bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("PAID");
            assertThat(order).extractingPath("$.installments").isEqualTo(3);
        });
    }

    /** A card Asaas holds for its risk analysis may still be paid, so the Order stays payable. */
    @Test
    void leavesTheOrderAwaitingWhenAsaasRefusesToCancelACheckoutWhoseCardItHoldsForRiskAnalysis() {
        String code = orders.placedCard(courses.onSale(newSlug()));
        asaas.cardSale(code, "AWAITING_RISK_ANALYSIS", 1, PRICE_CENTS, code);
        asaas.answerNextCancellationOf(Asaas.checkoutOf(code), Asaas.error(400, "invalid_status"));

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.BAD_GATEWAY);
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void leavesTheOrderAwaitingWhenAsaasCannotBeReachedToRereadARefusedCheckout() {
        String code = orders.placedCard(courses.onSale(newSlug()));
        asaas.answerNextCancellationOf(Asaas.checkoutOf(code), Asaas.error(400, "invalid_status"));
        asaas.answerNextChargeSearchOfCheckout(Asaas.checkoutOf(code), serverError());

        assertThat(orders.cancel(code)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }
}
