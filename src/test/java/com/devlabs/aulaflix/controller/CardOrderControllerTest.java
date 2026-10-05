package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.Asaas.field;
import static com.devlabs.aulaflix.StudentOrders.codeOf;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * A Student pays by card on Asaas's own page, a Checkout, so that the card never touches AulaFlix (ADR 0006): the Order
 * carries the Checkout's link, which lives 60 minutes, offers up to the Course's maximum installments, and returns the
 * Student to the Course's checkout page. Asaas is the WireMock stub, so what it was sent is part of the contract.
 */
class CardOrderControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PRICE_CENTS = 49700;

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    private AdminCourses courses;

    private StudentOrders orders;

    @BeforeEach
    void signInAnAdminAndAStudent() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos);
        BffApi bff = new BffApi(mvc);
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));
    }

    /** No CPF is asked: the Student gives Asaas their details on its page. */
    @Test
    void placesACardOrderForThePriceWithACheckoutThatLivesSixtyMinutes() {
        String slug = newSlug();
        long course = courses.onSale(slug);

        MvcTestResult placed = orders.placeCard(course);

        String code = codeOf(placed);
        assertThat(placed).hasStatus(HttpStatus.CREATED)
                .hasContentType(MediaType.APPLICATION_JSON)
                .hasHeader(HttpHeaders.LOCATION, "/v1/account/orders/" + code)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "code": "%s",
                          "status": "AWAITING_PAYMENT",
                          "method": "CARD",
                          "course": {"id": %d, "slug": "%s", "title": "Backend com Node.js"},
                          "amountCents": %d,
                          "createdAt": "%s",
                          "duplicatePayment": false,
                          "checkout": {
                            "url": "%s",
                            "expiresAt": "%s"
                          }
                        }""".formatted(code, course, slug, PRICE_CENTS, now(), Asaas.checkoutLinkOf(code),
                        now().plus(Duration.ofMinutes(60))));
    }

    @Test
    void asksAsaasForACheckoutForThePriceInUpToTheMaxInstallmentsReturningToTheCoursesCheckoutPage() {
        String slug = newSlug();
        long course = courses.onSale(slug);

        String code = orders.placedCard(course);

        String returnUrl = "http://localhost:3001/cursos/%s/comprar?pedido=%s".formatted(slug, code);
        assertThat(asaas.checkoutsCreatedUnder(code)).singleElement().satisfies(checkout -> {
            assertThat(field(checkout, "$.billingTypes")).isEqualTo(List.of("CREDIT_CARD"));
            assertThat(field(checkout, "$.chargeTypes")).isEqualTo(List.of("DETACHED", "INSTALLMENT"));
            assertThat(field(checkout, "$.installment.maxInstallmentCount")).isEqualTo(10);
            assertThat(field(checkout, "$.minutesToExpire")).isEqualTo(60);
            assertThat(field(checkout, "$.callback.successUrl")).isEqualTo(returnUrl);
            assertThat(field(checkout, "$.callback.expiredUrl")).isEqualTo(returnUrl);
            assertThat(field(checkout, "$.callback.cancelUrl")).isEqualTo(returnUrl + "&cancelado=1");
            assertThat(field(checkout, "$.items.length()")).isEqualTo(1);
            assertThat(field(checkout, "$.items[0].name")).isEqualTo("Backend com Node.js");
            assertThat(field(checkout, "$.items[0].description")).isEqualTo("Pedido %s: Backend com Node.js"
                    .formatted(code));
            assertThat(field(checkout, "$.items[0].quantity")).isEqualTo(1);
            assertThat(new BigDecimal(field(checkout, "$.items[0].value").toString())).isEqualByComparingTo("497.00");
            assertThat(JsonPath.parse(checkout).read("$", Map.class)).doesNotContainKey("customerData");
        });
    }

    /** Asaas takes an item's name of up to 30 characters; the description, of up to 150, holds the whole title. */
    @Test
    void cutsTheCoursesTitleToTheThirtyCharactersAsaasTakesForTheItemsName() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        String title = "Arquitetura de Software com Java, Spring e Microsserviços";
        courses.putDocument(course, JsonPath.parse(AdminCourses.fullDocument(slug, courses.freeLessonOf(course)))
                .set("$.title", title).jsonString());

        String code = orders.placedCard(course);

        assertThat(asaas.checkoutsCreatedUnder(code)).singleElement().satisfies(checkout -> {
            assertThat(field(checkout, "$.items[0].name")).isEqualTo("Arquitetura de Software com J…");
            assertThat(field(checkout, "$.items[0].description")).isEqualTo("Pedido %s: %s".formatted(code, title));
        });
    }

    @Test
    void keepsATitleOfThirtyCharactersWhole() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        String title = "Testes automatizados com Jest!";
        courses.putDocument(course, JsonPath.parse(AdminCourses.fullDocument(slug, courses.freeLessonOf(course)))
                .set("$.title", title).jsonString());

        String code = orders.placedCard(course);

        assertThat(title).hasSize(30);
        assertThat(asaas.checkoutsCreatedUnder(code)).singleElement()
                .satisfies(checkout -> assertThat(field(checkout, "$.items[0].name")).isEqualTo(title));
    }

    /** One installment is a single payment: Asaas's installment plan needs a count above one to mean anything. */
    @Test
    void offersASinglePaymentWhenTheCourseTakesNoInstallments() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        courses.putDocument(course, JsonPath.parse(AdminCourses.fullDocument(slug, courses.freeLessonOf(course)))
                .set("$.maxInstallments", 1).jsonString());

        String code = orders.placedCard(course);

        assertThat(asaas.checkoutsCreatedUnder(code)).singleElement().satisfies(checkout -> {
            assertThat(field(checkout, "$.chargeTypes")).isEqualTo(List.of("DETACHED"));
            assertThat(JsonPath.parse(checkout).read("$", Map.class)).doesNotContainKey("installment");
        });
    }

    @Test
    void takesTheCoursesPriceAtTheMomentTheOrderIsPlaced() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        courses.putDocument(course, JsonPath.parse(AdminCourses.fullDocument(slug, courses.freeLessonOf(course)))
                .set("$.priceCents", 29700).jsonString());

        MvcTestResult placed = orders.placeCard(course);

        assertThat(placed).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.amountCents").isEqualTo(29700);
        assertThat(asaas.checkoutsCreatedUnder(codeOf(placed))).singleElement().satisfies(checkout ->
                assertThat(new BigDecimal(field(checkout, "$.items[0].value").toString()))
                        .isEqualByComparingTo("297.00"));
    }

    @Test
    void answersTheCardOrderAlreadyAwaitingPaymentWithoutANewCheckout() {
        long course = courses.onSale(newSlug());
        MvcTestResult first = orders.placeCard(course);
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));

        MvcTestResult again = orders.placeCard(course);

        assertThat(again).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .doesNotContainHeader(HttpHeaders.LOCATION)
                .bodyJson().isStrictlyEqualTo(body(first));
        assertThat(asaas.checkoutsCreatedUnder(codeOf(first))).hasSize(1);
    }

    @Test
    void showsTheCheckoutOnTheOrdersOwnReadButNeverInTheList() {
        long course = courses.onSale(newSlug());
        String code = orders.placedCard(course);

        assertThat(orders.get(code)).hasStatusOk().bodyJson().extractingPath("$.checkout.url")
                .isEqualTo(Asaas.checkoutLinkOf(code));
        assertThat(orders.list()).hasStatusOk().bodyJson().satisfies(list -> {
            assertThat(list).extractingPath("$.items[0].code").isEqualTo(code);
            assertThat(list).extractingPath("$.items[0].method").isEqualTo("CARD");
            assertThat(list).doesNotHavePath("$.items[0].checkout");
        });
    }

    @Test
    void cancelsTheOrderWhenAsaasCannotMakeTheCheckoutAndLetsTheStudentTryAgain() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        asaas.answerNextCheckoutFor(slug, serverError());

        MvcTestResult failed = orders.placeCard(course);

        assertThat(failed).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, "30")
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/payment-unavailable");
        assertThat(orders.list()).bodyJson().extractingPath("$.items").asArray().isEmpty();
        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void answersUnavailableWhenAsaasTakesTooLongToMakeTheCheckout() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        asaas.answerNextCheckoutFor(slug, Asaas.tooLate());

        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void cancelsTheOrderWhenAsaasRefusesTheCheckoutAndLetsTheStudentTryAgain() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        asaas.answerNextCheckoutFor(slug, Asaas.error(400, "invalid_items"));

        MvcTestResult failed = orders.placeCard(course);

        assertThat(failed).hasStatus(HttpStatus.BAD_GATEWAY)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/payment-provider-error");
        assertThat(orders.list()).bodyJson().extractingPath("$.items").asArray().isEmpty();
        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.CREATED);
    }

    /** A Checkout answer without its link would leave the Student nowhere to pay. */
    @Test
    void cancelsTheOrderWhenAsaasAnswersACheckoutWithoutItsLink() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        asaas.answerNextCheckoutFor(slug, okJson("""
                {"id": "chk_without_link", "status": "ACTIVE"}"""));

        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.BAD_GATEWAY);
        assertThat(orders.placeCard(course)).hasStatus(HttpStatus.CREATED);
    }

    /** Cut to the microseconds PostgreSQL keeps. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
