package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.Asaas.field;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;

/**
 * When Asaas fails a placement: down, too slow, 5xx or 429 is a 503 the BFF retries after {@code Retry-After}; any
 * other 4xx is a 502. Either way the Order, written before Asaas was called, ends cancelled, any charge made under its
 * code is deleted, and the Student can place a new one.
 */
class PixOrderAsaasFailureTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    private long course;

    private StudentOrders orders;

    private String cpf;

    @BeforeEach
    void putACourseOnSaleAndSignInAStudent() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        course = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos)
                .onSale(newSlug());
        BffApi bff = new BffApi(mvc);
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));
        cpf = Cpfs.newCpf();
    }

    static Stream<Arguments> unavailable() {
        return Stream.of(
                Arguments.of("too late", Asaas.tooLate()),
                Arguments.of("connection reset", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
                Arguments.of("500", serverError()),
                Arguments.of("503", aResponse().withStatus(503)),
                Arguments.of("429", Asaas.error(429, "too_many_requests")));
    }

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("400", Asaas.error(400, "invalid_value")),
                Arguments.of("401", Asaas.error(401, "invalid_access_token")),
                Arguments.of("404 without a body", aResponse().withStatus(404)),
                Arguments.of("an unreadable 200", aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody("{\"id\": ")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailable")
    void answersPaymentUnavailableWhenAsaasCannotMakeTheCustomer(String failure, ResponseDefinitionBuilder answer) {
        asaas.answerNextCustomerWith(cpf, answer);

        assertPaymentUnavailable(orders.placePix(course, cpf));

        assertThat(orders.list()).bodyJson().extractingPath("$.items").asArray().isEmpty();
        assertThat(orders.placePix(course, cpf)).hasStatus(HttpStatus.CREATED);
        assertThat(asaas.chargesCreatedFor(Asaas.customerOf(cpf))).hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    void answersPaymentProviderErrorWhenAsaasRefusesTheCustomer(String failure, ResponseDefinitionBuilder answer) {
        asaas.answerNextCustomerWith(cpf, answer);

        assertPaymentProviderError(orders.placePix(course, cpf));

        assertThat(orders.placePix(course, cpf)).hasStatus(HttpStatus.CREATED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unavailable")
    void cancelsTheOrderAndDeletesItsChargeWhenAsaasCannotMakeTheCharge(String failure,
                                                                        ResponseDefinitionBuilder answer) {
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), answer);

        assertPaymentUnavailable(orders.placePix(course, cpf));

        String cancelled = onlyChargedOrder();
        assertCancelled(cancelled);
        assertThat(asaas.deletionsOf(Asaas.chargeOf(cancelled))).isOne();
        assertANewOrderCanBePlaced(cancelled);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    void cancelsTheOrderAndDeletesItsChargeWhenAsaasRefusesTheCharge(String failure,
                                                                     ResponseDefinitionBuilder answer) {
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), answer);

        assertPaymentProviderError(orders.placePix(course, cpf));

        String cancelled = onlyChargedOrder();
        assertCancelled(cancelled);
        assertThat(asaas.deletionsOf(Asaas.chargeOf(cancelled))).isOne();
        assertANewOrderCanBePlaced(cancelled);
    }

    /** A charge whose QR code cannot be read is useless to the Student: it is deleted with the Order cancelled. */
    @Test
    void deletesTheChargeWhoseQrCodeAsaasCannotGive() {
        String charge = "pay_" + UUID.randomUUID();
        asaas.answerNextQrCodeFor(Asaas.customerOf(cpf), charge, serverError());

        assertPaymentUnavailable(orders.placePix(course, cpf));

        assertThat(asaas.deletionsOf(charge)).isOne();
        assertCancelled(onlyChargedOrder());
    }

    @Test
    void answersPaymentProviderErrorForAQrCodeWithoutItsImage() {
        String charge = "pay_" + UUID.randomUUID();
        asaas.answerNextQrCodeFor(Asaas.customerOf(cpf), charge,
                aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"payload\": \"000201\"}"));

        assertPaymentProviderError(orders.placePix(course, cpf));

        assertThat(asaas.deletionsOf(charge)).isOne();
    }

    /** The search finds every charge under the code that is not deleted yet, and deletes only those. */
    @Test
    void deletesEveryChargeTheSearchFindsUnderTheCodeButTheDeletedOnes() {
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), serverError());
        asaas.answerNextChargeSearchWith(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("""
                        {"object": "list", "hasMore": false, "totalCount": 3, "limit": 10, "offset": 0,
                         "data": [{"id": "pay_first-of-%1$s", "deleted": false},
                                  {"id": "pay_deleted-of-%1$s", "deleted": true},
                                  {"id": "pay_second-of-%1$s", "deleted": false}]}""".formatted(cpf)));

        assertPaymentUnavailable(orders.placePix(course, cpf));

        assertThat(asaas.deletionsOf("pay_first-of-" + cpf)).isOne();
        assertThat(asaas.deletionsOf("pay_second-of-" + cpf)).isOne();
        assertThat(asaas.deletionsOf("pay_deleted-of-" + cpf)).isZero();
    }

    /** Reconciliation finds the charge later: the Student gets the same answer, and the Order is cancelled. */
    @Test
    void leavesTheChargeToReconciliationWhenAsaasCannotSearchEither() {
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), Asaas.tooLate());
        asaas.answerNextChargeSearchWith(serverError());

        assertPaymentUnavailable(orders.placePix(course, cpf));

        String cancelled = onlyChargedOrder();
        assertCancelled(cancelled);
        assertThat(asaas.deletionsOf(Asaas.chargeOf(cancelled))).isZero();
    }

    /** The customer was made before the charge failed: the next Pix asks for no CPF and makes no other. */
    @Test
    void keepsTheCustomerMadeBeforeTheChargeFailed() {
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), serverError());
        assertPaymentUnavailable(orders.placePix(course, cpf));

        assertThat(orders.placePix(course, null)).hasStatus(HttpStatus.CREATED);
        assertThat(asaas.customersCreatedWith(cpf)).hasSize(1);
    }

    /** The code the failed placement sent Asaas: it never reached the Student. */
    private String onlyChargedOrder() {
        List<String> charges = asaas.chargesCreatedFor(Asaas.customerOf(cpf));
        assertThat(charges).hasSize(1);
        return (String) field(charges.getFirst(), "$.externalReference");
    }

    private void assertCancelled(String code) {
        assertThat(orders.get(code)).hasStatusOk().bodyJson().satisfies(order -> {
            assertThat(order).extractingPath("$.status").isEqualTo("CANCELLED");
            assertThat(order).doesNotHavePath("$.pix");
        });
        assertThat(orders.list()).bodyJson().extractingPath("$.items").asArray().isEmpty();
    }

    private void assertANewOrderCanBePlaced(String cancelled) {
        MvcTestResult placed = orders.placePix(course, null);
        assertThat(placed).hasStatus(HttpStatus.CREATED);
        assertThat(StudentOrders.codeOf(placed)).isNotEqualTo(cancelled);
    }

    private static void assertPaymentUnavailable(MvcTestResult placed) {
        assertThat(placed).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, "30")
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/payment-unavailable",
                          "title": "Payment unavailable",
                          "status": 503,
                          "detail": "The payment provider cannot be reached now. Try again in Retry-After seconds.",
                          "instance": "/v1/account/orders"
                        }""");
    }

    private static void assertPaymentProviderError(MvcTestResult placed) {
        assertThat(placed).hasStatus(HttpStatus.BAD_GATEWAY)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .doesNotContainHeader(HttpHeaders.RETRY_AFTER)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/payment-provider-error",
                          "title": "Payment provider error",
                          "status": 502,
                          "detail": "The payment provider refused the payment. It has been logged.",
                          "instance": "/v1/account/orders"
                        }""");
    }
}
