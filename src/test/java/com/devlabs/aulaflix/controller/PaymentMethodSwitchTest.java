package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.StudentOrders.codeOf;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;

/**
 * One method, one Asaas attempt per Order: a Student who places an Order by the other method while one awaits payment
 * gets a new Order, once the awaiting one is cancelled at Asaas, its Pix charge deleted or its Checkout cancelled. Asaas
 * is the WireMock stub, so what it was sent is part of the contract.
 */
class PaymentMethodSwitchTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PIX_PRICE_CENTS = 44730;

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    @Autowired
    private JdbcTemplate jdbc;

    private AdminCourses courses;

    private StudentOrders orders;

    private String slug;

    private long course;

    @BeforeEach
    void putACourseOnSaleAndSignInAStudent() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos);
        slug = newSlug();
        course = courses.onSale(slug);
        BffApi bff = new BffApi(mvc);
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));
    }

    /** A payment the switch finds emails its Student: none is sent. */
    @AfterEach
    void discardWhatWasQueued() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void switchingFromPixToCardDeletesThePixChargeAndPlacesACardOrder() {
        String pix = orders.placedPix(course, Cpfs.newCpf());

        MvcTestResult card = orders.placeCard(course);

        String code = codeOf(card);
        assertThat(card).hasStatus(HttpStatus.CREATED)
                .hasHeader(HttpHeaders.LOCATION, "/v1/account/orders/" + code)
                .bodyJson().satisfies(order -> {
                    assertThat(order).extractingPath("$.method").isEqualTo("CARD");
                    assertThat(order).extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
                    assertThat(order).extractingPath("$.checkout.url").isEqualTo(Asaas.checkoutLinkOf(code));
                });
        assertThat(code).isNotEqualTo(pix);
        assertThat(asaas.deletionsOf(Asaas.chargeOf(pix))).isOne();
        assertThat(orders.get(pix)).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    }

    @Test
    void switchingFromCardToPixCancelsTheCheckoutAndPlacesAPixOrder() {
        String card = orders.placedCard(course);

        MvcTestResult pix = orders.placePix(course, Cpfs.newCpf());

        String code = codeOf(pix);
        assertThat(pix).hasStatus(HttpStatus.CREATED).bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.method").isEqualTo("PIX");
            assertThat(order).extractingPath("$.amountCents").isEqualTo(PIX_PRICE_CENTS);
            assertThat(order).extractingPath("$.pix.copyPasteCode")
                    .isEqualTo(Asaas.copyPasteCodeOf(Asaas.chargeOf(code)));
        });
        assertThat(asaas.cancellationsOf(Asaas.checkoutOf(card))).isOne();
        assertThat(orders.get(card)).bodyJson().extractingPath("$.status").isEqualTo("CANCELLED");
    }

    /** The request is checked before anything is cancelled, so a bad one leaves the Student's Order payable. */
    @Test
    void keepsTheCardOrderWhenTheStudentsFirstPixComesWithoutItsCpf() {
        String card = orders.placedCard(course);

        assertThat(orders.placePix(course, null)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.errors[0]").isEqualTo(java.util.Map.of("field", "cpf", "code", "required"));
        assertThat(asaas.cancellationsOf(Asaas.checkoutOf(card))).isZero();
        assertThat(orders.get(card)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void keepsThePixOrderAndPlacesNothingWhenAsaasCannotDeleteItsCharge() {
        String pix = orders.placedPix(course, Cpfs.newCpf());
        asaas.answerNextDeletionOf(Asaas.chargeOf(pix), serverError());

        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE).bodyJson()
                .extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/payment-unavailable");
        assertThat(asaas.checkoutsCreatedFor(slug)).isEmpty();
        assertThat(orders.get(pix)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void keepsThePixOrderAndPlacesNothingWhenAsaasRefusesToDeleteItsPendingCharge() {
        String pix = orders.placedPix(course, Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(pix), "PENDING", PIX_PRICE_CENTS, pix, false);
        asaas.answerNextDeletionOf(Asaas.chargeOf(pix), Asaas.error(400, "invalid_action"));

        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.BAD_GATEWAY).bodyJson()
                .extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/payment-provider-error");
        assertThat(asaas.checkoutsCreatedFor(slug)).isEmpty();
        assertThat(orders.get(pix)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    /** The Student paid the Pix, then chose card before the webhook came: the payment wins, and they have the Course. */
    @Test
    void refusesTheSwitchWhenAsaasShowsTheAwaitingOrderPaid() {
        String pix = orders.placedPix(course, Cpfs.newCpf());
        asaas.chargeIs(Asaas.chargeOf(pix), "RECEIVED", PIX_PRICE_CENTS, pix, false);
        asaas.answerNextDeletionOf(Asaas.chargeOf(pix), Asaas.error(400, "invalid_action"));

        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.CONFLICT).bodyJson()
                .extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/already-enrolled");
        assertThat(asaas.checkoutsCreatedFor(slug)).isEmpty();
        assertThat(orders.get(pix)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
    }
}
