package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * A Student's own Orders: the list, newest first and without the never-paid ones, and each Order on its own, in any
 * state. Only the Order's own read carries the Pix QR code, and only while it awaits payment.
 */
class StudentOrdersReadTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

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
        orders = newStudent();
    }

    @Test
    void listsNoOrderBeforeTheFirst() {
        assertThat(orders.list()).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("{\"items\": []}");
    }

    @Test
    void listsTheOrdersNewestFirstWithoutTheirQrCodes() {
        String cpf = Cpfs.newCpf();
        String olderSlug = newSlug();
        long older = courses.onSale(olderSlug);
        String newerSlug = newSlug();
        long newer = courses.onSale(newerSlug);
        String olderCode = orders.placedPix(older, cpf);
        clock.set(clock.instant().plus(Duration.ofMinutes(1)));
        String newerCode = orders.placedPix(newer, null);

        assertThat(orders.list()).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {
                  "items": [
                    {
                      "code": "%s", "status": "AWAITING_PAYMENT", "method": "PIX",
                      "course": {"id": %d, "slug": "%s", "title": "Backend com Node.js"},
                      "amountCents": 44730, "createdAt": "%s", "duplicatePayment": false
                    },
                    {
                      "code": "%s", "status": "AWAITING_PAYMENT", "method": "PIX",
                      "course": {"id": %d, "slug": "%s", "title": "Backend com Node.js"},
                      "amountCents": 44730, "createdAt": "%s", "duplicatePayment": false
                    }
                  ]
                }""".formatted(newerCode, newer, newerSlug, now(), olderCode, older, olderSlug,
                now().minus(Duration.ofMinutes(1))));
    }

    @Test
    void leavesTheCancelledOrdersOutOfTheList() {
        String cpf = Cpfs.newCpf();
        long course = courses.onSale(newSlug());
        asaas.answerNextChargeFor(Asaas.customerOf(cpf), serverError());
        assertThat(orders.placePix(course, cpf)).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        String placed = orders.placedPix(course, null);

        assertThat(orders.list()).bodyJson().extractingPath("$.items[*].code").asArray().containsExactly(placed);
    }

    @Test
    void readsAnOrderAwaitingPaymentWithItsQrCode() {
        long course = courses.onSale(newSlug());
        MvcTestResult placed = orders.placePix(course, Cpfs.newCpf());

        MvcTestResult read = orders.get(StudentOrders.codeOf(placed));

        assertThat(read).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo(body(placed));
    }

    @Test
    void neverShowsAnotherStudentsOrder() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());

        assertOrderNotFound(newStudent().get(code), code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"K7M2Q9XA", "k7m2q9xa", "0", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"})
    void answersAnUnknownCodeOfAnyShapeAsNotFound(String code) {
        assertOrderNotFound(orders.get(code), code);
    }

    private StudentOrders newStudent() {
        BffApi bff = new BffApi(mvc);
        return new StudentOrders(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));
    }

    private void assertOrderNotFound(MvcTestResult read, String code) {
        assertThat(read).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/order-not-found",
                          "title": "Order not found",
                          "status": 404,
                          "detail": "The Student has no Order with this code.",
                          "instance": "/v1/account/orders/%s",
                          "timestamp": "%s"
                        }""".formatted(code, clock.instant()));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
