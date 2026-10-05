package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminOrders;
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
import com.devlabs.aulaflix.service.OrderReconciliation;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * A card Order as the Admin sees and refunds it. A card paid in installments is one charge per installment, all under
 * one installment plan: refunding one charge would return one installment, so the refund goes through the plan, which
 * Asaas, the WireMock stub, refunds whole. A single payment is refunded as its charge, as a Pix is.
 */
class CardRefundTest extends IntegrationTest {

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
    private JdbcTemplate jdbc;

    private AdminOrders adminOrders;

    private StudentOrders orders;

    private long course;

    @BeforeEach
    void putACourseOnSaleAndSignInAStudent() {
        String adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        course = new AdminCourses(mvc, adminToken, storedVideos).onSale(newSlug());
        adminOrders = new AdminOrders(mvc, adminToken);
        BffApi bff = new BffApi(mvc);
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));
    }

    /** Payments and refunds queue emails, and reconciliation follows every Order of the suite: all discarded. */
    @AfterEach
    void discardWhatWasQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void showsTheAdminACardOrdersInstallmentsAndAsaasIds() {
        String code = paidByCard(10);

        assertThat(adminOrders.get(code)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "PAID", "method": "CARD", "amountCents": %d, "installments": 10,
                 "asaasChargeId": "pay_%s_1", "asaasCheckoutId": "%s", "asaasInstallmentId": "%s"}"""
                .formatted(code, PRICE_CENTS, code, Asaas.checkoutOf(code), Asaas.installmentOf(code)));
    }

    @Test
    void leavesOutTheIdsAPixOrderHasNone() {
        String code = orders.placedPix(course, Cpfs.newCpf());

        assertThat(adminOrders.get(code)).hasStatusOk().bodyJson().satisfies(order -> {
            assertThat(order).doesNotHavePath("$.installments");
            assertThat(order).doesNotHavePath("$.asaasCheckoutId");
            assertThat(order).doesNotHavePath("$.asaasInstallmentId");
        });
    }

    @Test
    void refundsACardPaidInInstallmentsThroughItsInstallmentPlan() {
        String code = paidByCard(10);

        assertThat(adminOrders.refund(code)).hasStatusOk().bodyJson().extractingPath("$.status")
                .isEqualTo("REFUNDING");

        assertThat(asaas.refundsOfInstallmentPlan(Asaas.installmentOf(code))).isOne();
        for (int installment = 1; installment <= 10; installment++) {
            assertThat(asaas.refundsOf("pay_%s_%d".formatted(code, installment))).isZero();
        }
    }

    @Test
    void refundsACardPaidAtOnceAsItsCharge() {
        String code = paidByCard(1);

        assertThat(adminOrders.refund(code)).hasStatusOk().bodyJson().extractingPath("$.status")
                .isEqualTo("REFUNDING");

        assertThat(asaas.refundsOf("pay_%s_1".formatted(code))).isOne();
        assertThat(asaas.refundsOfInstallmentPlan(Asaas.installmentOf(code))).isZero();
    }

    /** The plan's refund shows on each of its charges; reconciliation reads the one the Order keeps. */
    @Test
    void reconciliationRefundsTheOrderOnceItsInstallmentPlansRefundIsDone() {
        String code = paidByCard(10);
        adminOrders.refund(code);
        String charge = "pay_%s_1".formatted(code);
        asaas.answerChargeReadsWith(charge, okJson("""
                {"object": "payment", "id": "%s", "billingType": "CREDIT_CARD", "status": "REFUNDED",
                 "value": 49.70, "externalReference": null, "deleted": false, "checkoutSession": "%s",
                 "installment": "%s", "installmentNumber": 1,
                 "refunds": [{"dateCreated": "2026-10-05 14:45:03", "status": "DONE", "value": 49.70}]}"""
                .formatted(charge, Asaas.checkoutOf(code), Asaas.installmentOf(code))));

        reconciliation.reconcile();

        assertThat(adminOrders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("REFUNDED");
    }

    private String paidByCard(int installments) {
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "CONFIRMED", installments, PRICE_CENTS, null).getFirst();
        new AsaasWebhooks(mvc).deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED",
                PRICE_CENTS, code));
        worker.processPending();
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
        return code;
    }
}
