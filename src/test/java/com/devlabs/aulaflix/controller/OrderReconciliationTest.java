package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.Asaas.field;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
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
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.OrderReconciliation;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * No paying Student waits on a lost webhook: reconciliation re-reads, from Asaas, the WireMock stub, every Order that
 * has awaited payment longer than {@code aulaflix.asaas.reconciliation-delay}, and applies what its charge shows. It
 * also deletes the charges a failed placement may have left at Asaas under a cancelled Order's code.
 */
class OrderReconciliationTest extends IntegrationTest {

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
    private OrderReconciliation reconciliation;

    @Autowired
    private WebhookWorker worker;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Value("${aulaflix.asaas.reconciliation-delay}")
    private Duration delay;

    private String adminEmail;

    private String studentEmail;

    private StudentOrders orders;

    private long course;

    private String cpf;

    @BeforeEach
    void putACourseOnSaleAndSignInAStudent() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        course = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD), storedVideos)
                .onSale(newSlug());
        BffApi bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(studentEmail, PASSWORD));
        cpf = Cpfs.newCpf();
    }

    /** The webhook was lost: reconciliation pays the Order once, and the webhook that comes late changes nothing. */
    @Test
    void paysAnOrderWhoseConfirmedPaymentNoWebhookBroughtAndOnlyOnce() {
        String code = orders.placedPix(course, cpf);
        String charge = chargeIs(code, "CONFIRMED");
        clock.set(clock.instant().plus(delay));
        Instant paidAt = clock.instant().truncatedTo(ChronoUnit.MICROS);

        reconciliation.reconcile();
        reconciliation.reconcile();
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        new AsaasWebhooks(mvc).deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED",
                PIX_PRICE_CENTS, code));
        worker.processPending();
        outbox.drain();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "paidAt": "%s", "duplicatePayment": false}""".formatted(paidAt));
        assertThat(studentsEnrollments()).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "ORDER", "orderCode": "%s", "course": {"id": %d}}],
                 "totalItems": 1}""".formatted(code, course));
        assertThat(purchaseEmails()).hasSize(1);
    }

    @Test
    void leavesAnOrderAloneUntilItHasAwaitedPaymentLongerThanTheDelay() {
        String code = orders.placedPix(course, cpf);
        String charge = chargeIs(code, "CONFIRMED");
        clock.set(clock.instant().plus(delay).minusSeconds(1));

        reconciliation.reconcile();

        assertThat(asaas.readsOf(charge)).isZero();
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void leavesAnOrderWhoseChargeIsUnpaidAwaitingPayment() {
        String code = orders.placedPix(course, cpf);
        String charge = chargeIs(code, "PENDING");
        clock.set(clock.instant().plus(delay));

        reconciliation.reconcile();

        assertThat(asaas.readsOf(charge)).isOne();
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
        assertThat(asaas.deletionsOf(charge)).isZero();
    }

    @Test
    void leavesTheOrderAwaitingPaymentWhileAsaasCannotBeReachedAndPaysItOnTheNextRun() {
        String code = orders.placedPix(course, cpf);
        String charge = chargeIs(code, "CONFIRMED");
        asaas.answerNextChargeReadWith(charge, Asaas.tooLate());
        clock.set(clock.instant().plus(delay));

        reconciliation.reconcile();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");

        reconciliation.reconcile();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
    }

    /** The placement failed, and neither its charge's id nor the search for its code came back. */
    @Test
    void deletesTheChargeAFailedPlacementLeftUnderTheCancelledOrdersCodeOnce() {
        String code = cancelledLeavingItsCharge();
        clock.set(clock.instant().plus(delay));

        reconciliation.reconcile();
        reconciliation.reconcile();

        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isOne();
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    }

    /** A charge whose creation timed out may reach Asaas after the placement's search: the search waits the delay. */
    @Test
    void searchesForTheChargesOfACancelledOrderOnlyOnceTheDelayIsUp() {
        String code = cancelledLeavingItsCharge();
        clock.set(clock.instant().plus(delay).minusSeconds(1));

        reconciliation.reconcile();

        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isZero();
    }

    @Test
    void leavesTheChargeOfACancelledOrderWhileAsaasCannotBeReachedAndDeletesItOnTheNextRun() {
        String code = cancelledLeavingItsCharge();
        clock.set(clock.instant().plus(delay));
        asaas.answerNextChargeSearchWith(serverError());

        reconciliation.reconcile();

        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isZero();

        reconciliation.reconcile();

        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isOne();
    }

    /** The placement deleted its charge, by the id Asaas gave, at once: there is nothing left to look for. */
    @Test
    void leavesAloneACancelledOrderWhoseChargeThePlacementDeleted() {
        String charge = "pay_" + UUID.randomUUID();
        asaas.answerNextQrCodeFor(Asaas.customerOf(cpf), charge, serverError());
        assertThat(orders.placePix(course, cpf)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        clock.set(clock.instant().plus(delay));

        reconciliation.reconcile();

        assertThat(asaas.deletionsOf(charge)).isOne();
        assertThat(asaas.deletionsOf(Asaas.chargeOf(onlyChargedOrder()))).isZero();
    }

    /** Asaas made the charge after the API stopped waiting, then could not be searched: the charge stays there. */
    private String cancelledLeavingItsCharge() {
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), Asaas.tooLate());
        asaas.answerNextChargeSearchWith(serverError());
        assertThat(orders.placePix(course, cpf)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        String code = onlyChargedOrder();
        assertThat(asaas.deletionsOf(Asaas.chargeOf(code))).isZero();
        return code;
    }

    /** The code the failed placement sent Asaas: it never reached the Student. */
    private String onlyChargedOrder() {
        List<String> charges = asaas.chargesCreatedFor(Asaas.customerOf(cpf));
        assertThat(charges).hasSize(1);
        return (String) field(charges.getFirst(), "$.externalReference");
    }

    private String chargeIs(String code, String status) {
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, status, PIX_PRICE_CENTS, code, false);
        return charge;
    }

    /** The Student's Enrollments, as an Admin signed in now finds them, whatever the clock did to earlier sessions. */
    private MvcTestResult studentsEnrollments() {
        return new AdminEnrollments(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD))
                .list("email=" + studentEmail);
    }

    private List<Mailpit.Email> purchaseEmails() {
        return mailpit.to(studentEmail).stream()
                .filter(email -> email.subject().equals("Compra confirmada: " + COURSE_TITLE))
                .toList();
    }
}
