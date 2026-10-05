package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminOrders;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.AsaasWebhooks;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredOrders;
import com.devlabs.aulaflix.StoredOutboxEmails;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.WebhookWorker;

/**
 * The Admin finds any Order, in every state, to answer a Student: the list, paginated and filtered, and the read, with
 * what deciding a refund takes. Every test filters by what only it made, its Student or its Course, since the whole
 * suite's Orders share the table.
 */
class AdminOrderControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
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
    private JdbcTemplate jdbc;

    private String adminEmail;

    private AdminCourses courses;

    private AdminOrders adminOrders;

    private String studentEmail;

    private String studentName;

    private String studentToken;

    private StudentOrders orders;

    @BeforeEach
    void signInAnAdminAndAStudent() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        adminOrders = new AdminOrders(mvc, adminToken);
        BffApi bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        studentName = "Bia " + UUID.randomUUID();
        studentToken = AdminApi.tokenOf(new StudentApi(bff).signUp(studentName, studentEmail, PASSWORD));
        orders = new StudentOrders(bff, studentToken);
    }

    /** A Duplicate payment alerts every Admin of the suite: what it queued is discarded. */
    @AfterEach
    void discardTheAlerts() {
        new StoredOutboxEmails(jdbc).discardPending();
    }

    @Test
    void readsAPaidOrderWithEverythingARefundIsDecidedFrom() {
        long course = courses.onSale(newSlug());
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String code = orders.placedPix(course, Cpfs.newCpf());
        clock.set(clock.instant().plus(Duration.ofMinutes(3)));
        Instant paidAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        confirm(code);
        long studentId = new StoredAccounts(jdbc).find(studentEmail).orElseThrow().id();

        assertThat(adminOrders.get(code)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "PAID", "method": "PIX", "course": {"id": %d, "title": "Backend com Node.js"},
                 "amountCents": 44730, "createdAt": "%s", "paidAt": "%s", "duplicatePayment": false,
                 "student": {"id": %d, "email": "%s", "name": "%s"},
                 "listPriceCents": 49700, "pixDiscountPercent": 10, "daysSincePayment": 0,
                 "enrollment": {"status": "ACTIVE", "startedAt": "%s"},
                 "asaasChargeId": "%s"}"""
                .formatted(code, course, createdAt, paidAt, studentId, studentEmail, studentName, paidAt,
                        Asaas.chargeOf(code)));
        assertThat(adminOrders.get(code)).bodyJson().doesNotHavePath("$.refundRequestedAt")
                .doesNotHavePath("$.refundRequestedBy").doesNotHavePath("$.refundedAt")
                .doesNotHavePath("$.pix").doesNotHavePath("$.enrollment.endedAt");
    }

    @Test
    void countsTheWholeDaysSincePaymentByTheClock() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        confirm(code);

        clock.set(clock.instant().plus(Duration.ofDays(3)).minusSeconds(1));
        assertThat(signedInAgain().get(code)).bodyJson().extractingPath("$.daysSincePayment").isEqualTo(2);

        clock.set(clock.instant().plusSeconds(1));
        assertThat(signedInAgain().get(code)).bodyJson().extractingPath("$.daysSincePayment").isEqualTo(3);
    }

    @Test
    void readsAnOrderAwaitingPaymentWithoutPaymentNorEnrollment() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());

        assertThat(adminOrders.get(code)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "AWAITING_PAYMENT", "asaasChargeId": "%s"}"""
                .formatted(code, Asaas.chargeOf(code)));
        assertThat(adminOrders.get(code)).bodyJson().doesNotHavePath("$.paidAt")
                .doesNotHavePath("$.daysSincePayment").doesNotHavePath("$.enrollment");
    }

    @Test
    void readingAnUnknownOrderIsNotFound() {
        assertThat(adminOrders.get("ZZZZZZZZ")).hasStatus(HttpStatus.NOT_FOUND).bodyJson()
                .extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/order-not-found");
    }

    @Test
    void listsEveryStateNewestFirst() {
        long course = courses.onSale(newSlug());
        String cancelled = orders.placedPix(course, Cpfs.newCpf());
        new StoredOrders(jdbc).cancel(cancelled);
        clock.set(clock.instant().plusSeconds(1));
        String paid = orders.placedPix(course, null);
        confirm(paid);

        assertThat(adminOrders.list("email=" + studentEmail)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "status": "PAID", "student": {"email": "%s"},
                            "enrollment": {"status": "ACTIVE"}},
                           {"code": "%s", "status": "CANCELLED"}],
                 "page": 0, "size": 20, "totalItems": 2, "totalPages": 1}"""
                .formatted(paid, studentEmail, cancelled));
    }

    @Test
    void filtersByStatus() {
        long course = courses.onSale(newSlug());
        String cancelled = orders.placedPix(course, Cpfs.newCpf());
        new StoredOrders(jdbc).cancel(cancelled);
        String paid = orders.placedPix(course, null);
        confirm(paid);

        assertThat(adminOrders.list("email=%s&status=CANCELLED".formatted(studentEmail))).bodyJson()
                .isLenientlyEqualTo("""
                        {"items": [{"code": "%s"}], "totalItems": 1}""".formatted(cancelled));
        assertThat(adminOrders.list("email=%s&status=PAID".formatted(studentEmail))).bodyJson()
                .isLenientlyEqualTo("""
                        {"items": [{"code": "%s"}], "totalItems": 1}""".formatted(paid));
    }

    @Test
    void filtersByCourse() {
        long course = courses.onSale(newSlug());
        String inCourse = orders.placedPix(course, Cpfs.newCpf());
        orders.placedPix(courses.onSale(newSlug()), null);

        assertThat(adminOrders.list("courseId=" + course)).bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s", "course": {"id": %d}}], "totalItems": 1}""".formatted(inCourse, course));
    }

    @Test
    void filtersByTheStudentsEmailTrimmedAndLowerCased() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());

        assertThat(adminOrders.list("email= " + studentEmail.toUpperCase(Locale.ROOT) + " "))
                .bodyJson().isLenientlyEqualTo("""
                        {"items": [{"code": "%s"}], "totalItems": 1}""".formatted(code));
    }

    @Test
    void filtersByDuplicatePayment() {
        long course = courses.onSale(newSlug());
        String first = orders.placedPix(course, Cpfs.newCpf());
        confirm(first);
        String duplicate = new StoredOrders(jdbc).insertAwaitingCopyOf(first);
        confirm(duplicate);

        assertThat(adminOrders.list("email=%s&duplicatePayment=true".formatted(studentEmail))).bodyJson()
                .isLenientlyEqualTo("""
                        {"items": [{"code": "%s", "duplicatePayment": true}], "totalItems": 1}"""
                        .formatted(duplicate));
        assertThat(adminOrders.list("email=%s&duplicatePayment=false".formatted(studentEmail))).bodyJson()
                .isLenientlyEqualTo("""
                        {"items": [{"code": "%s", "duplicatePayment": false}], "totalItems": 1}"""
                        .formatted(first));
        assertThat(adminOrders.list("email=" + studentEmail)).bodyJson().isLenientlyEqualTo("""
                {"totalItems": 2}""");
        assertThat(adminOrders.get(duplicate)).bodyJson().doesNotHavePath("$.enrollment");
    }

    @Test
    void pagesTheList() {
        long course = courses.onSale(newSlug());
        String oldest = orders.placedPix(course, Cpfs.newCpf());
        new StoredOrders(jdbc).cancel(oldest);
        clock.set(clock.instant().plusSeconds(1));
        String middle = orders.placedPix(course, null);
        new StoredOrders(jdbc).cancel(middle);
        clock.set(clock.instant().plusSeconds(1));
        orders.placedPix(course, null);

        assertThat(adminOrders.list("courseId=%d&page=1&size=2".formatted(course))).bodyJson().isLenientlyEqualTo("""
                {"items": [{"code": "%s"}], "page": 1, "size": 2, "totalItems": 3, "totalPages": 2}"""
                .formatted(oldest));
        assertThat(adminOrders.list("courseId=%d&page=0&size=2".formatted(course))).bodyJson()
                .extractingPath("$.items[1].code").isEqualTo(middle);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-1", "99999999999999999999"})
    void aCourseIdOfAnyShapeFindsNothing(String courseId) {
        assertThat(adminOrders.list("courseId=" + courseId)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"items": [], "totalItems": 0}""");
    }

    @Test
    void refusesAnUnknownStatus() {
        assertThat(adminOrders.list("status=SHIPPED")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .isLenientlyEqualTo("""
                        {"type": "https://aulaflix.com.br/problems/invalid-request",
                         "errors": [{"field": "status", "code": "invalid-format"}]}""");
    }

    @Test
    void refusesADuplicatePaymentFilterThatIsNoBoolean() {
        assertThat(adminOrders.list("duplicatePayment=yes")).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .isLenientlyEqualTo("""
                        {"errors": [{"field": "duplicatePayment", "code": "invalid-format"}]}""");
    }

    @Test
    void refusesAnyOtherQueryParameter() {
        assertThat(adminOrders.list("sort=createdAt,asc")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void aStudentCannotListNorReadOrders() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());
        AdminOrders asStudent = new AdminOrders(mvc, studentToken);

        assertThat(asStudent.list("")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(asStudent.get(code)).hasStatus(HttpStatus.FORBIDDEN);
    }

    @Test
    void noSessionCannotListReadNorRefundOrders() {
        String code = orders.placedPix(courses.onSale(newSlug()), Cpfs.newCpf());

        assertThat(mvc.get().uri("/v1/admin/orders")).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/v1/admin/orders/" + code)).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/v1/admin/orders/%s/refund".formatted(code))).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    /** The Admin signed in now, whatever the clock did to earlier sessions. */
    private AdminOrders signedInAgain() {
        return new AdminOrders(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD));
    }

    /** Asaas confirms the charge, then its webhook is delivered and the worker runs. */
    private void confirm(String code) {
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, "CONFIRMED", PIX_PRICE_CENTS, code, false);
        assertThat(new AsaasWebhooks(mvc).deliver(paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge,
                "CONFIRMED", PIX_PRICE_CENTS, code))).hasStatusOk();
        worker.processPending();
    }
}
