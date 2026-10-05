package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AsaasWebhooks.newEventId;
import static com.devlabs.aulaflix.AsaasWebhooks.paymentEvent;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.devlabs.aulaflix.StoredWebhookEvents;
import com.devlabs.aulaflix.StoredWebhookEvents.StoredWebhookEvent;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.devlabs.aulaflix.service.WebhookWorker;
import com.jayway.jsonpath.JsonPath;

/**
 * Only a charge Asaas confirms opens access. Asaas's webhook is stored and answered at once; the worker then re-reads
 * the charge from Asaas, the WireMock stub, and acts on what the re-read shows, never on the event's body.
 */
class AsaasWebhookTest extends IntegrationTest {

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
    private WebhookWorker worker;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private AdminCourses courses;

    private AdminEnrollments enrollments;

    private AsaasWebhooks webhooks;

    private StoredWebhookEvents storedEvents;

    private BffApi bff;

    private String studentEmail;

    private String studentToken;

    private StudentOrders orders;

    @BeforeEach
    void signInAnAdminAndAStudent() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(email, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        enrollments = new AdminEnrollments(mvc, adminToken);
        webhooks = new AsaasWebhooks(mvc);
        storedEvents = new StoredWebhookEvents(jdbc);
        bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        studentToken = new StudentApi(bff).signedUp(studentEmail, PASSWORD);
        orders = new StudentOrders(bff, studentToken);
    }

    @Test
    void aConfirmedPaymentPaysTheOrderAndGrantsAnEnrollmentWhoseOriginIsTheOrder() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "CONFIRMED");

        MvcTestResult delivered = deliver("PAYMENT_CONFIRMED", charge, code);
        worker.processPending();

        assertThat(delivered).hasStatusOk();
        assertThat(delivered.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(orders.get(code)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"code": "%s", "status": "PAID", "paidAt": "%s", "duplicatePayment": false}"""
                .formatted(code, clock.instant().truncatedTo(ChronoUnit.MICROS)));
        assertThat(enrollments.list("email=" + studentEmail)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "ORDER", "orderCode": "%s", "course": {"id": %d}}],
                 "totalItems": 1}""".formatted(code, course));
        assertThat(playback(lesson)).hasStatusOk();
    }

    @Test
    void aReceivedPixPaysTheOrderToo() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "RECEIVED");

        assertThat(deliver("PAYMENT_RECEIVED", charge, code)).hasStatusOk();
        worker.processPending();

        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
        assertThat(playback(lesson)).hasStatusOk();
    }

    @Test
    void emailsTheStudentARecordOfThePurchase() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "CONFIRMED");

        deliver("PAYMENT_CONFIRMED", charge, code);
        worker.processPending();
        outbox.drain();

        assertThat(purchaseEmails()).singleElement().satisfies(email -> {
            assertThat(email.from()).isEqualTo("AulaFlix <contato@aulaflix.com.br>");
            assertThat(email.to()).containsExactly(studentEmail);
            assertThat(email.text()).contains("Olá, Bia!", "pedido " + code, "R$ 447,30", COURSE_TITLE,
                    "http://localhost:3001/aprender/" + slug);
        });
    }

    /** Asaas delivers at least once, and a Pix under a cautionary block goes CONFIRMED, then RECEIVED. */
    @Test
    void grantsOneEnrollmentAndSendsOneEmailHoweverManyTimesThePaymentIsConfirmed() {
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "CONFIRMED");
        String confirmed = paymentEvent(newEventId(), "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS,
                code);
        assertThat(webhooks.deliver(confirmed)).hasStatusOk();
        worker.processPending();
        Instant paidAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        clock.set(clock.instant().plus(Duration.ofMinutes(10)));
        chargeIs(code, "RECEIVED");

        MvcTestResult replayed = webhooks.deliver(confirmed);
        MvcTestResult received = deliver("PAYMENT_RECEIVED", charge, code);
        MvcTestResult confirmedAgain = deliver("PAYMENT_CONFIRMED", charge, code);
        worker.processPending();
        outbox.drain();

        assertThat(replayed).hasStatusOk();
        assertThat(received).hasStatusOk();
        assertThat(confirmedAgain).hasStatusOk();
        assertThat(enrollments.list("email=" + studentEmail)).bodyJson().extractingPath("$.totalItems").isEqualTo(1);
        assertThat(purchaseEmails()).hasSize(1);
        assertThat(orders.get(code)).bodyJson().extractingPath("$.paidAt").isEqualTo(paidAt.toString());
    }

    @Test
    void storesARepeatedEventIdOnce() {
        String eventId = newEventId();
        String event = paymentEvent(eventId, "PAYMENT_CONFIRMED", "pay_unknown" + UUID.randomUUID(), "CONFIRMED",
                PIX_PRICE_CENTS, "K7M2Q9XA");

        assertThat(webhooks.deliver(event)).hasStatusOk();
        assertThat(webhooks.deliver(event)).hasStatusOk();

        assertThat(storedEvents.statesOf(eventId)).containsExactly("PENDING");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "the-wrong-token-of-the-tests-0123456", Asaas.WEBHOOK_TOKEN + "0",
            BffApi.KEY})
    void refusesADeliveryWithoutTheTokenAndStoresNothing(String token) {
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "CONFIRMED");
        String eventId = newEventId();

        MvcTestResult refused = webhooks.deliver(token, paymentEvent(eventId, "PAYMENT_CONFIRMED", charge,
                "CONFIRMED", PIX_PRICE_CENTS, code));
        worker.processPending();

        assertThat(refused).hasStatus(HttpStatus.FORBIDDEN).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"type": "https://aulaflix.com.br/problems/invalid-webhook-token", "status": 403,
                         "title": "Invalid webhook token", "instance": "/v1/webhooks/asaas"}""");
        assertThat(storedEvents.statesOf(eventId)).isEmpty();
        assertThat(asaas.readsOf(charge)).isZero();
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    /** The edge blanks the BFF's headers on the webhook; were they sent, they would change nothing. */
    @Test
    void needsNeitherTheBffKeyNorAClientIp() {
        MvcTestResult withBffHeaders = mvc.post().uri("/v1/webhooks/asaas")
                .header(AsaasWebhooks.TOKEN_HEADER, Asaas.WEBHOOK_TOKEN)
                .header("AulaFlix-BFF-Key", "not-the-bff-key")
                .header("AulaFlix-Client-IP", "not-an-ip")
                .contentType(MediaType.APPLICATION_JSON)
                .content(paymentEvent(newEventId(), "PAYMENT_CREATED", "pay_x", "PENDING", 100, "K7M2Q9XA"))
                .exchange();

        assertThat(withBffHeaders).hasStatusOk();
    }

    @Test
    void storesAnEventItDoesNotHandleAsIgnored() {
        String eventId = newEventId();

        MvcTestResult delivered = webhooks.deliver("""
                {"id": "%s", "event": "PAYMENT_CREATED", "payment": {"id": "pay_%s"}}"""
                .formatted(eventId, UUID.randomUUID()));

        assertThat(delivered).hasStatusOk();
        assertThat(delivered.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(storedEvents.statesOf(eventId)).containsExactly("IGNORED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json %s", "[\"%s\"]", "{\"event\": \"PAYMENT_CONFIRMED\", \"note\": \"%s\"}",
            "{\"id\": 42, \"note\": \"%s\"}", "{\"id\": \"\", \"note\": \"%s\"}", "{\"note\": \"%s\"", "\"%s\""})
    void storesABodyThatIsNoEventAsUnprocessable(String template) {
        byte[] body = template.formatted(UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);

        MvcTestResult delivered = mvc.post().uri("/v1/webhooks/asaas")
                .header(AsaasWebhooks.TOKEN_HEADER, Asaas.WEBHOOK_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();

        assertThat(delivered).hasStatusOk();
        assertThat(delivered.getResponse().getContentAsByteArray()).isEmpty();
        assertThat(storedEvents.withBody(body)).containsExactly(new StoredWebhookEvent(null, "UNPROCESSABLE"));
    }

    /** Asaas's ids are far shorter; one longer than the inbox keeps cannot be Asaas's. */
    @Test
    void storesAnEventIdOfUpTo255Characters() {
        String unique = newEventId();
        String longest = unique + "9".repeat(255 - unique.length());
        String tooLong = longest + "9";
        byte[] tooLongEvent = "{\"id\": \"%s\", \"event\": \"PAYMENT_CREATED\"}".formatted(tooLong)
                .getBytes(StandardCharsets.UTF_8);

        webhooks.deliver("{\"id\": \"%s\", \"event\": \"PAYMENT_CREATED\"}".formatted(longest));
        webhooks.deliver(new String(tooLongEvent, StandardCharsets.UTF_8));

        assertThat(storedEvents.statesOf(longest)).containsExactly("IGNORED");
        assertThat(storedEvents.withBody(tooLongEvent))
                .containsExactly(new StoredWebhookEvent(null, "UNPROCESSABLE"));
    }

    @Test
    void takesAChargeIdOfUpTo64Characters() {
        String longestEvent = newEventId();
        String tooLongEvent = newEventId();

        webhooks.deliver(paymentEvent(longestEvent, "PAYMENT_CONFIRMED", "pay_" + "1".repeat(60), "CONFIRMED",
                PIX_PRICE_CENTS, "K7M2Q9XA"));
        webhooks.deliver(paymentEvent(tooLongEvent, "PAYMENT_CONFIRMED", "pay_" + "1".repeat(61), "CONFIRMED",
                PIX_PRICE_CENTS, "K7M2Q9XA"));

        assertThat(storedEvents.statesOf(longestEvent)).containsExactly("PENDING");
        assertThat(storedEvents.statesOf(tooLongEvent)).containsExactly("UNPROCESSABLE");
    }

    /** The edge's own limit; {@code AsaasWebhookIT} checks it over real HTTP too. */
    @Test
    void takesABodyOfUpTo256KilobytesAndRefusesALargerOne() {
        String longestEvent = newEventId();
        String tooLongEvent = newEventId();

        MvcTestResult longest = webhooks.deliver(eventOfLength(longestEvent, 256 * 1024));
        MvcTestResult tooLong = webhooks.deliver(eventOfLength(tooLongEvent, 256 * 1024 + 1));

        assertThat(longest).hasStatusOk();
        assertThat(storedEvents.statesOf(longestEvent)).containsExactly("IGNORED");
        assertThat(tooLong).hasStatus(HttpStatus.CONTENT_TOO_LARGE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON).bodyJson().isLenientlyEqualTo("""
                        {"type": "https://aulaflix.com.br/problems/content-too-large", "status": 413,
                         "title": "Content too large", "detail": "A webhook's body is at most 256 KB."}""");
        assertThat(storedEvents.statesOf(tooLongEvent)).isEmpty();
    }

    @Test
    void storesAPaymentEventWithoutItsChargeAsUnprocessable() {
        String eventId = newEventId();

        assertThat(webhooks.deliver("""
                {"id": "%s", "event": "PAYMENT_CONFIRMED", "payment": {"value": 447.3}}""".formatted(eventId)))
                .hasStatusOk();

        assertThat(storedEvents.statesOf(eventId)).containsExactly("UNPROCESSABLE");
    }

    static Stream<Arguments> rereadsThatGrantNothing() {
        return Stream.of(
                Arguments.of("another reference", "CONFIRMED", PIX_PRICE_CENTS, "ZZZZZZZZ", false, "UNPROCESSABLE"),
                Arguments.of("another amount", "CONFIRMED", PIX_PRICE_CENTS - 1, null, false, "UNPROCESSABLE"),
                Arguments.of("a larger amount", "RECEIVED", PIX_PRICE_CENTS + 1, null, false, "UNPROCESSABLE"),
                Arguments.of("an unpaid status", "PENDING", PIX_PRICE_CENTS, null, false, "IGNORED"),
                Arguments.of("an overdue status", "OVERDUE", PIX_PRICE_CENTS, null, false, "IGNORED"),
                Arguments.of("a deleted charge", "CONFIRMED", PIX_PRICE_CENTS, null, true, "IGNORED"));
    }

    /** Whatever the body claims, only the re-read counts: the body here always claims a confirmed payment. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("rereadsThatGrantNothing")
    void grantsNothingWhenTheReReadContradictsAPayment(String reread, String status, int valueCents,
                                                       String reference, boolean deleted, String outcome) {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, status, valueCents, reference == null ? code : reference, deleted);
        String eventId = newEventId();

        assertThat(webhooks.deliver(paymentEvent(eventId, "PAYMENT_CONFIRMED", charge, "CONFIRMED",
                PIX_PRICE_CENTS, code))).hasStatusOk();
        worker.processPending();
        outbox.drain();

        assertThat(storedEvents.statesOf(eventId)).containsExactly(outcome);
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
        assertThat(enrollments.list("email=" + studentEmail)).bodyJson().extractingPath("$.totalItems").isEqualTo(0);
        assertThat(playback(lesson)).hasStatus(HttpStatus.CONFLICT);
        assertThat(purchaseEmails()).isEmpty();
    }

    @Test
    void ignoresAnEventForAChargeNoOrderHas() {
        String charge = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        asaas.chargeIs(charge, "CONFIRMED", PIX_PRICE_CENTS, "K7M2Q9XA", false);
        String eventId = newEventId();

        webhooks.deliver(paymentEvent(eventId, "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS,
                "K7M2Q9XA"));
        worker.processPending();

        assertThat(storedEvents.statesOf(eventId)).containsExactly("IGNORED");
    }

    @Test
    void leavesTheEventPendingWhileAsaasCannotBeReachedAndPaysOnTheNextRun() {
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = chargeIs(code, "CONFIRMED");
        asaas.answerNextChargeReadWith(charge, Asaas.tooLate());
        String eventId = newEventId();
        webhooks.deliver(paymentEvent(eventId, "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS, code));

        worker.processPending();

        assertThat(storedEvents.statesOf(eventId)).containsExactly("PENDING");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");

        worker.processPending();

        assertThat(storedEvents.statesOf(eventId)).containsExactly("PROCESSED");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("PAID");
    }

    @Test
    void settlesAnEventAsUnprocessableWhenAsaasRefusesTheReRead() {
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        String charge = Asaas.chargeOf(code);
        asaas.answerChargeReadsWith(charge, Asaas.error(404, "not_found"));
        String eventId = newEventId();
        webhooks.deliver(paymentEvent(eventId, "PAYMENT_CONFIRMED", charge, "CONFIRMED", PIX_PRICE_CENTS, code));

        worker.processPending();

        assertThat(storedEvents.statesOf(eventId)).containsExactly("UNPROCESSABLE");
        assertThat(orders.get(code)).bodyJson().extractingPath("$.status").isEqualTo("AWAITING_PAYMENT");
    }

    @Test
    void refusesAnAdminWhoEndsTheEnrollmentAnOrderGranted() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String code = orders.placedPix(course, Cpfs.newCpf());
        deliver("PAYMENT_CONFIRMED", chargeIs(code, "CONFIRMED"), code);
        worker.processPending();
        long enrollment = ((Number) JsonPath.read(AdminApi.body(enrollments.list("email=" + studentEmail)),
                "$.items[0].id")).longValue();

        MvcTestResult ended = enrollments.end(enrollment, "Pedido de reembolso.");

        assertThat(ended).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {"type": "https://aulaflix.com.br/problems/paid-enrollment", "status": 409,
                         "title": "Paid Enrollment",
                         "detail": "An Enrollment granted by an Order ends only with a Refund of that Order.",
                         "instance": "/v1/admin/enrollments/%d/status"}""".formatted(enrollment));
        assertThat(enrollments.get(Long.toString(enrollment))).bodyJson().extractingPath("$.status")
                .isEqualTo("ACTIVE");
        assertThat(playback(lesson)).hasStatusOk();
    }

    @Test
    void refusesANewOrderForTheCourseOnceItIsPaid() {
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        deliver("PAYMENT_CONFIRMED", chargeIs(code, "CONFIRMED"), code);
        worker.processPending();

        MvcTestResult again = orders.placePix(course, null);

        assertThat(again).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/already-enrolled");
    }

    /** The Order is still paid, but grants nothing: the Student keeps the Enrollment they had, and an Admin refunds. */
    @Test
    void grantsNothingForAPaymentWhileTheStudentAlreadyHasTheCourse() {
        long course = courses.onSale(newSlug());
        String code = orders.placedPix(course, Cpfs.newCpf());
        enrollments.granted(studentEmail, course);

        deliver("PAYMENT_CONFIRMED", chargeIs(code, "CONFIRMED"), code);
        worker.processPending();
        outbox.drain();

        assertThat(orders.get(code)).bodyJson().isLenientlyEqualTo("""
                {"status": "PAID", "duplicatePayment": true}""");
        assertThat(enrollments.list("email=" + studentEmail)).bodyJson().isLenientlyEqualTo("""
                {"items": [{"status": "ACTIVE", "origin": "MANUAL"}], "totalItems": 1}""");
        assertThat(purchaseEmails()).isEmpty();
    }

    private String chargeIs(String code, String status) {
        String charge = Asaas.chargeOf(code);
        asaas.chargeIs(charge, status, PIX_PRICE_CENTS, code, false);
        return charge;
    }

    private MvcTestResult deliver(String event, String charge, String code) {
        return webhooks.deliver(paymentEvent(newEventId(), event, charge, "CONFIRMED", PIX_PRICE_CENTS, code));
    }

    /** An event Asaas could send, padded to exactly this many bytes. */
    private static String eventOfLength(String eventId, int length) {
        String start = "{\"id\": \"%s\", \"event\": \"PAYMENT_CREATED\", \"padding\": \"".formatted(eventId);
        String end = "\"}";
        return start + "x".repeat(length - start.length() - end.length()) + end;
    }

    private List<Mailpit.Email> purchaseEmails() {
        return mailpit.to(studentEmail).stream()
                .filter(email -> email.subject().equals("Compra confirmada: " + COURSE_TITLE))
                .toList();
    }

    private long paidLessonOf(long course) {
        return courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"), "Rotas no Express",
                "rotas-no-express", "five-seconds.mp4");
    }

    private MvcTestResult playback(long lesson) {
        return bff.get("/v1/lessons/%d/playback".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .exchange();
    }
}
