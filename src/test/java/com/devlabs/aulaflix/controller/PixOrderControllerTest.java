package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.Asaas.field;
import static com.devlabs.aulaflix.StudentOrders.codeOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminEnrollments;
import com.devlabs.aulaflix.Asaas;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.Cpfs;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * A Student pays for an On sale Course by Pix on AulaFlix's page (ADR 0006): the first Pix asks for the CPF, which makes
 * the Student's one Asaas customer, and every Pix is a direct charge for the Pix price, shown as a QR code that lives
 * 30 minutes. Asaas is the WireMock stub, so what it was sent is part of the contract.
 */
class PixOrderControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int PIX_PRICE_CENTS = 44730;
    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private Asaas asaas;

    private String adminToken;

    private AdminCourses courses;

    private String studentEmail;

    private StudentOrders orders;

    @BeforeEach
    void signInAnAdminAndAStudent() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        adminToken = new AdminApi(mvc).sessionToken(email, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        BffApi bff = new BffApi(mvc);
        studentEmail = StudentApi.newEmail();
        orders = new StudentOrders(bff, new StudentApi(bff).signedUp(studentEmail, PASSWORD));
    }

    @Test
    void asksForTheCpfOnTheStudentsFirstPix() {
        long course = courses.onSale(newSlug());

        MvcTestResult placed = orders.placePix(course, null);

        assertInvalidCpf(placed, "required");
    }

    @Test
    void placesAPixOrderForThePixPriceWithAQrCodeThatLivesThirtyMinutes() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        String cpf = Cpfs.newCpf();

        MvcTestResult placed = orders.placePix(course, cpf);

        String code = codeOf(placed);
        assertThat(code).matches("[2-9A-HJKMNP-Z]{8}");
        assertThat(placed).hasStatus(HttpStatus.CREATED)
                .hasContentType(MediaType.APPLICATION_JSON)
                .hasHeader(HttpHeaders.LOCATION, "/v1/account/orders/" + code)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "code": "%s",
                          "status": "AWAITING_PAYMENT",
                          "method": "PIX",
                          "course": {"id": %d, "slug": "%s", "title": "Backend com Node.js"},
                          "amountCents": %d,
                          "createdAt": "%s",
                          "duplicatePayment": false,
                          "pix": {
                            "qrCodePng": "%s",
                            "copyPasteCode": "%s",
                            "expiresAt": "%s"
                          }
                        }""".formatted(code, course, slug, PIX_PRICE_CENTS, now(), Asaas.QR_CODE_PNG,
                        Asaas.copyPasteCodeOf(Asaas.chargeOf(code)), now().plus(Duration.ofMinutes(30))));
    }

    @Test
    void makesOneAsaasCustomerWithNotificationsOffAndOneChargeUnderTheOrdersCode() {
        long course = courses.onSale(newSlug());
        String cpf = Cpfs.newCpf();

        String code = orders.placedPix(course, cpf);

        assertThat(asaas.customersCreatedWith(cpf)).singleElement().satisfies(customer -> {
            assertThat(field(customer, "$.name")).isEqualTo("Bia");
            assertThat(field(customer, "$.notificationDisabled")).isEqualTo(true);
        });
        assertThat(asaas.chargesCreatedFor(Asaas.customerOf(cpf))).singleElement().satisfies(charge -> {
            assertThat(field(charge, "$.billingType")).isEqualTo("PIX");
            assertThat(field(charge, "$.externalReference")).isEqualTo(code);
            assertThat(new BigDecimal(field(charge, "$.value").toString())).isEqualByComparingTo("447.30");
            assertThat(field(charge, "$.dueDate")).isEqualTo(LocalDate.ofInstant(clock.instant(), BRAZIL).toString());
        });
    }

    /** At 23:30 in São Paulo it is already the next day in UTC; Asaas's due date is Brazil's day. */
    @Test
    void dueTheChargeOnTheDayItIsInBrazil() {
        LocalDate yesterday = LocalDate.ofInstant(clock.instant(), BRAZIL).minusDays(1);
        clock.set(yesterday.atTime(23, 30).atZone(BRAZIL).toInstant());
        long course = courses.onSale(newSlug());
        String cpf = Cpfs.newCpf();

        orders.placedPix(course, cpf);

        assertThat(asaas.chargesCreatedFor(Asaas.customerOf(cpf))).singleElement()
                .satisfies(charge -> assertThat(field(charge, "$.dueDate")).isEqualTo(yesterday.toString()));
    }

    @Test
    void sendsTheApiKeyAndAUserAgentToAsaas() {
        long course = courses.onSale(newSlug());
        String cpf = Cpfs.newCpf();

        orders.placedPix(course, cpf);

        assertThat(asaas.chargeRequestsFor(Asaas.customerOf(cpf))).singleElement().satisfies(request -> {
            assertThat(request.getHeader("access_token")).isEqualTo(Asaas.API_KEY);
            assertThat(request.getHeader(HttpHeaders.USER_AGENT)).isEqualTo("aulaflix-api");
        });
    }

    @Test
    void takesTheCpfWithItsDotsAndDash() {
        long course = courses.onSale(newSlug());
        String cpf = Cpfs.newCpf();

        assertThat(orders.placePix(course, Cpfs.punctuated(cpf))).hasStatus(HttpStatus.CREATED);

        assertThat(asaas.customersCreatedWith(cpf)).hasSize(1);
    }

    @Test
    void asksNoCpfAndMakesNoCustomerOnTheStudentsNextPix() {
        long first = courses.onSale(newSlug());
        long second = courses.onSale(newSlug());
        String cpf = Cpfs.newCpf();
        orders.placedPix(first, cpf);

        MvcTestResult placed = orders.placePix(second, null);

        assertThat(placed).hasStatus(HttpStatus.CREATED);
        assertThat(asaas.customersCreatedWith(cpf)).hasSize(1);
        assertThat(asaas.chargesCreatedFor(Asaas.customerOf(cpf))).extracting(charge -> field(charge,
                "$.externalReference")).hasSize(2).contains(codeOf(placed));
    }

    /** The CPF only makes the customer: once there is one, a CPF sent anyway is not checked, nor sent anywhere. */
    @Test
    void ignoresACpfSentOnceTheStudentHasACustomer() {
        long first = courses.onSale(newSlug());
        long second = courses.onSale(newSlug());
        String cpf = Cpfs.newCpf();
        orders.placedPix(first, cpf);

        assertThat(orders.placePix(second, "123")).hasStatus(HttpStatus.CREATED);
        assertThat(asaas.customersCreatedWith("123")).isEmpty();
    }

    @Test
    void answersTheOrderAlreadyAwaitingPaymentWithoutANewCharge() {
        long course = courses.onSale(newSlug());
        String cpf = Cpfs.newCpf();
        MvcTestResult first = orders.placePix(course, cpf);
        clock.set(clock.instant().plus(Duration.ofMinutes(5)));

        MvcTestResult again = orders.placePix(course, null);

        assertThat(again).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .doesNotContainHeader(HttpHeaders.LOCATION)
                .bodyJson().isStrictlyEqualTo(body(first));
        assertThat(asaas.chargesCreatedFor(Asaas.customerOf(cpf))).hasSize(1);
    }

    @Test
    void takesTheCoursesPriceAtTheMomentTheOrderIsPlaced() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        String cpf = Cpfs.newCpf();
        courses.putDocument(course, JsonPath.parse(AdminCourses.fullDocument(slug, courses.freeLessonOf(course)))
                .set("$.priceCents", 29700).set("$.pixDiscountPercent", 15).jsonString());

        MvcTestResult placed = orders.placePix(course, cpf);

        // 29700 less 15%, 25245, the Pix price the catalog shows
        assertThat(placed).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.amountCents").isEqualTo(25245);
        assertThat(asaas.chargesCreatedFor(Asaas.customerOf(cpf))).singleElement().satisfies(charge ->
                assertThat(new BigDecimal(field(charge, "$.value").toString())).isEqualByComparingTo("252.45"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "5299822472", "529982247250", "529.982.247-2", "5299822472a", "52998224726",
            "52998224715", "11111111111", "00000000000", "529_982_247_25"})
    void refusesACpfThatIsNotOne(String cpf) {
        long course = courses.onSale(newSlug());

        assertInvalidCpf(orders.placePix(course, cpf), "invalid-cpf");
        assertThat(asaas.customersCreatedWith(cpf)).isEmpty();
    }

    /** 529.982.247-25 is a valid CPF, the base of most of the refused ones above. */
    @Test
    void takesTheCpfTheRefusedOnesAreOneStepFrom() {
        long course = courses.onSale(newSlug());

        assertThat(orders.placePix(course, "529.982.247-25")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesACpfAsaasRefusesAndLetsTheStudentTryAnother() {
        long course = courses.onSale(newSlug());
        String refused = Cpfs.newCpf();
        asaas.answerNextCustomerWith(refused, Asaas.error(400, "invalid_cpfCnpj"));

        assertInvalidCpf(orders.placePix(course, refused), "invalid-cpf");

        String cpf = Cpfs.newCpf();
        assertThat(orders.placePix(course, cpf)).hasStatus(HttpStatus.CREATED);
        assertThat(asaas.chargesCreatedFor(Asaas.customerOf(refused))).isEmpty();
    }

    @Test
    void refusesAnAdmin() {
        long course = courses.onSale(newSlug());
        StudentOrders admin = new StudentOrders(new BffApi(mvc), adminToken);

        assertForbidden(admin.placePix(course, Cpfs.newCpf()));
        assertForbidden(admin.list());
        assertForbidden(admin.get("K7M2Q9XA"));
    }

    @Test
    void asksForASession() {
        StudentOrders visitor = new StudentOrders(new BffApi(mvc), "no-such-token");

        assertThat(visitor.placePix(courses.onSale(newSlug()), Cpfs.newCpf()))
                .hasStatus(HttpStatus.UNAUTHORIZED).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }

    @Test
    void sellsOnlyAnOnSaleCourse() {
        String cpf = Cpfs.newCpf();

        assertCourseNotForSale(orders.placePix(courses.draft(newSlug()), cpf));
        assertCourseNotForSale(orders.placePix(courses.announced(newSlug()), cpf));
        assertCourseNotForSale(orders.placePix(Long.MAX_VALUE, cpf));
        assertThat(asaas.customersCreatedWith(cpf)).isEmpty();
    }

    @Test
    void refusesAStudentAlreadyEnrolled() {
        long course = courses.onSale(newSlug());
        new AdminEnrollments(mvc, adminToken).granted(studentEmail, course);
        String cpf = Cpfs.newCpf();

        assertThat(orders.placePix(course, cpf)).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/already-enrolled");
        assertThat(asaas.customersCreatedWith(cpf)).isEmpty();
    }

    @Test
    void sellsTheCourseAgainOnceTheEnrollmentEnded() {
        long course = courses.onSale(newSlug());
        AdminEnrollments enrollments = new AdminEnrollments(mvc, adminToken);
        enrollments.ended(enrollments.granted(studentEmail, course));

        assertThat(orders.placePix(course, Cpfs.newCpf())).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesARequestWithoutTheCourseOrTheMethod() {
        assertThat(orders.place("{\"cpf\": \"%s\"}".formatted(Cpfs.newCpf()))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.errors").isEqualTo(List.of(
                        Map.of("field", "courseId", "code", "required"),
                        Map.of("field", "method", "code", "required")));
    }

    @Test
    void refusesAMethodItDoesNotTake() {
        long course = courses.onSale(newSlug());

        assertThat(orders.place("{\"courseId\": %d, \"method\": \"BOLETO\"}".formatted(course)))
                .hasStatus(HttpStatus.BAD_REQUEST).bodyJson().extractingPath("$.errors")
                .isEqualTo(List.of(Map.of("field", "method", "code", "invalid-format")));
    }

    private void assertInvalidCpf(MvcTestResult placed, String code) {
        assertThat(placed).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "status": 400,
                          "errors": [{"field": "cpf", "code": "%s"}]
                        }""".formatted(code));
    }

    private static void assertForbidden(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/forbidden");
    }

    private void assertCourseNotForSale(MvcTestResult placed) {
        assertThat(placed).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/course-not-for-sale",
                          "title": "Course not for sale",
                          "status": 409,
                          "detail": "Only an On sale Course can be bought.",
                          "instance": "/v1/account/orders"
                        }""");
    }

    /** Cut to the microseconds PostgreSQL keeps. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
