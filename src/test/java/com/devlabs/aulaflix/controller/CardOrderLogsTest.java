package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.OrderExpiry;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * A card Order's flows log the Order's code and Asaas's ids, never the Student's email nor Asaas's key. An Asaas
 * refusal that a retry will not fix is logged at ERROR, with what Asaas said; an outage at WARN.
 */
@ExtendWith(OutputCaptureExtension.class)
class CardOrderLogsTest extends IntegrationTest {

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
    private OrderExpiry expiry;

    @Autowired
    private JdbcTemplate jdbc;

    private String slug;

    private long course;

    @BeforeEach
    void putACourseOnSale() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        slug = newSlug();
        course = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos).onSale(slug);
    }

    @AfterEach
    void discardWhatThePaymentQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void logsTheOrderAndTheCheckoutButNoEmailOrKey(CapturedOutput output) {
        String email = StudentApi.newEmail();
        StudentOrders orders = signedUp(email);
        asaas.answerNextCheckoutFor(slug, serverError());
        orders.placeCard(course);
        String code = orders.placedCard(course);
        orders.placeCard(course);
        String charge = asaas.cardSale(code, "CONFIRMED", 1, PRICE_CENTS, null).getFirst();

        new AsaasWebhooks(mvc).deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED",
                PRICE_CENTS, code));
        worker.processPending();

        assertThat(output.getAll()).contains("placed card Order " + code, "Order " + code + " was paid")
                .doesNotContain(email, Asaas.API_KEY);
    }

    @Test
    void logsTheDeclineOfACardRiskAnalysisRejected(CapturedOutput output) {
        StudentOrders orders = signedUp(StudentApi.newEmail());
        String code = orders.placedCard(course);
        String charge = asaas.cardSale(code, "PENDING", 1, PRICE_CENTS, null).getFirst();

        new AsaasWebhooks(mvc).deliver(paymentEvent(newEventId(), "PAYMENT_REPROVED_BY_RISK_ANALYSIS", charge,
                "PENDING", PRICE_CENTS, code));
        worker.processPending();

        assertThat(output.getAll()).contains("Order %s was declined: Asaas's risk analysis rejected the card"
                .formatted(code));
    }

    @Test
    void logsWhyACardHeldForRiskAnalysisOutlivesItsExpiry(CapturedOutput output) {
        String code = signedUp(StudentApi.newEmail()).placedCard(course);
        asaas.cardSale(code, "AWAITING_RISK_ANALYSIS", 1, PRICE_CENTS, null);
        clock.set(clock.instant().plus(Duration.ofMinutes(60)));

        expiry.expireDue();

        assertThat(output.getAll()).contains("Left card Order %s awaiting payment past its expiry: Asaas holds it for "
                .formatted(code) + "risk analysis");
    }

    @Test
    void logsAnAsaasRefusalAtErrorWithWhatAsaasSaid(CapturedOutput output) {
        asaas.answerNextCheckoutFor(slug, Asaas.error(400, "invalid_items"));

        signedUp(StudentApi.newEmail()).placeCard(course);

        assertThat(output.getAll()).containsPattern(
                "ERROR .*Refused POST /v1/account/orders: payment-provider-error; Order [2-9A-Z]{8} cancelled; "
                        + "Asaas refused creating a Checkout: HTTP 400 \\[invalid_items]");
    }

    @Test
    void logsAnAsaasOutageAtWarnWithItsCause(CapturedOutput output) {
        asaas.answerNextCheckoutFor(slug, serverError());

        signedUp(StudentApi.newEmail()).placeCard(course);

        assertThat(output.getAll()).containsPattern(
                "WARN .*Refused POST /v1/account/orders: payment-unavailable; Order [2-9A-Z]{8} cancelled; "
                        + "Asaas failed creating a Checkout: HTTP 500");
    }

    private StudentOrders signedUp(String email) {
        BffApi bff = new BffApi(mvc);
        return new StudentOrders(bff, new StudentApi(bff).signedUp(email, PASSWORD));
    }
}
