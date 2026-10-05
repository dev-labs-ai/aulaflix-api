package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredCourses;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

class AdminCourseControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    /** Stands for a field taken out of the document, where {@code null} sets it to JSON's null. */
    private static final Object ABSENT = new Object();

    @Autowired
    private AccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StoredVideos storedVideos;

    private String token;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
    }

    @Test
    void createsADraftFromASlugAndATitle() {
        String slug = newSlug();

        MvcTestResult result = create(slug, "Backend com Node.js");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        long id = idOf(result);
        assertThat(result).hasHeader(HttpHeaders.LOCATION, "/v1/admin/courses/" + id)
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "id": %d,
                          "slug": "%s",
                          "title": "Backend com Node.js",
                          "about": [],
                          "learn": [],
                          "audience": [],
                          "plannedTopics": [],
                          "faq": [],
                          "status": "DRAFT",
                          "readiness": {
                            "comingSoon": ["summary", "area", "icon", "tone", "about", "learn", "audience",
                                           "plannedTopics"],
                            "onSale": ["summary", "area", "icon", "tone", "about", "learn", "audience", "priceCents",
                                       "maxInstallments", "freeLessonId"]
                          }
                        }""".formatted(id, slug));
    }

    @Test
    void readsTheCourseItCreated() {
        MvcTestResult created = create(newSlug(), "Frontend com React");

        MvcTestResult read = mvc.get().uri(created.getResponse().getHeader(HttpHeaders.LOCATION))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();

        assertThat(read).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo(body(created));
    }

    @Test
    void listsEveryCourseInEveryStateUnpaginatedInTheOrderTheyWereCreated() {
        List<MvcTestResult> drafts = Stream.generate(() -> create(newSlug(), "Backend com Node.js"))
                .limit(21)
                .toList();
        long comingSoon = new StoredCourses(jdbc).insertComingSoon(newSlug(), Instant.parse("2026-09-01T12:00:00Z"));

        MvcTestResult list = mvc.get().uri("/v1/admin/courses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();

        assertThat(list).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().extractingPath("$").asMap().containsOnlyKeys("items");
        List<Long> expectedIds = Stream.concat(drafts.stream().map(AdminCourseControllerTest::idOf),
                Stream.of(comingSoon)).toList();
        List<Long> listedIds = JsonPath.<List<Number>>read(body(list), "$.items[*].id").stream()
                .map(Number::longValue)
                .filter(expectedIds::contains)
                .toList();
        assertThat(listedIds).isEqualTo(expectedIds);
        for (MvcTestResult draft : drafts) {
            assertThat(listedItem(list, idOf(draft))).isEqualTo(JsonPath.read(body(draft), "$"));
        }
        assertThat(listedItem(list, comingSoon)).containsEntry("status", "COMING_SOON");
    }

    @Test
    void replacesTheWholeDocumentAndReadsBackExactlyWhatWasPut() {
        long id = idOf(create(newSlug(), "Backend"));
        String slug = newSlug();
        String document = """
                {
                  "slug": "%s",
                  "title": "Backend com Node.js",
                  "summary": "Construa APIs REST com Node.js e TypeScript, até colocar o serviço no ar.",
                  "area": "BACKEND",
                  "icon": "SERVER",
                  "tone": "CORAL",
                  "about": ["Quase todo produto depende de um backend.", "Este curso constrói uma API do zero."],
                  "learn": ["Projetar rotas e respostas.", "Validar entradas.", "Escrever testes."],
                  "audience": ["Para devs frontend."],
                  "plannedTopics": ["Fundamentos de APIs.", "Autenticação."],
                  "faq": [
                    {"question": "Preciso saber JavaScript?", "answer": "Sim, o básico."},
                    {"question": "Tem certificado?", "answer": "Não."}
                  ],
                  "priceCents": 49700,
                  "pixDiscountPercent": 10,
                  "maxInstallments": 10
                }""".formatted(slug);
        String expected = """
                {
                  "id": %d,
                  "slug": "%s",
                  "title": "Backend com Node.js",
                  "summary": "Construa APIs REST com Node.js e TypeScript, até colocar o serviço no ar.",
                  "area": "BACKEND",
                  "icon": "SERVER",
                  "tone": "CORAL",
                  "about": ["Quase todo produto depende de um backend.", "Este curso constrói uma API do zero."],
                  "learn": ["Projetar rotas e respostas.", "Validar entradas.", "Escrever testes."],
                  "audience": ["Para devs frontend."],
                  "plannedTopics": ["Fundamentos de APIs.", "Autenticação."],
                  "faq": [
                    {"question": "Preciso saber JavaScript?", "answer": "Sim, o básico."},
                    {"question": "Tem certificado?", "answer": "Não."}
                  ],
                  "priceCents": 49700,
                  "pixDiscountPercent": 10,
                  "maxInstallments": 10,
                  "status": "DRAFT",
                  "readiness": {"comingSoon": [], "onSale": ["freeLessonId"]}
                }""".formatted(id, slug);

        assertThat(put(id, document)).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo(expected);
        assertThat(get(id)).hasStatusOk().bodyJson().isStrictlyEqualTo(expected);
    }

    @Test
    void takesBackTheDocumentItReadWithWhatOnlyReadsShow() {
        long id = idOf(create(newSlug(), "Backend"));
        assertThat(put(id, fullDocument(newSlug()))).hasStatusOk();
        String read = body(get(id));

        assertThat(put(id, read)).hasStatusOk().bodyJson().isStrictlyEqualTo(read);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(read);
    }

    @Test
    void clearsEveryFieldTheDocumentLeavesOut() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, fullDocument(slug))).hasStatusOk();

        MvcTestResult result = put(id, """
                {"slug": "%s", "title": "Backend com Node.js"}""".formatted(slug));

        String expected = """
                {
                  "id": %d,
                  "slug": "%s",
                  "title": "Backend com Node.js",
                  "about": [],
                  "learn": [],
                  "audience": [],
                  "plannedTopics": [],
                  "faq": [],
                  "status": "DRAFT",
                  "readiness": {
                    "comingSoon": ["summary", "area", "icon", "tone", "about", "learn", "audience", "plannedTopics"],
                    "onSale": ["summary", "area", "icon", "tone", "about", "learn", "audience", "priceCents",
                               "maxInstallments", "freeLessonId"]
                  }
                }""".formatted(id, slug);
        assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo(expected);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void refusesAnInvalidFieldOfTheDocumentWithItsCode(String description, String path, Object value, String field,
                                                       String code) {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        DocumentContext document = JsonPath.parse(fullDocument(slug));

        MvcTestResult result = put(id, (value == ABSENT ? document.delete(path) : document.set(path, value))
                .jsonString());

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "status": 400,
                          "instance": "/v1/admin/courses/%d",
                          "errors": [{"field": "%s", "code": "%s"}]
                        }""".formatted(id, field, code));
    }

    static Stream<Arguments> invalidDocuments() {
        String tooLongItem = "a".repeat(1001);
        return Stream.of(
                Arguments.of("no slug", "$.slug", ABSENT, "slug", "required"),
                Arguments.of("slug with upper case", "$.slug", "Backend", "slug", "invalid-format"),
                Arguments.of("slug of 81 characters", "$.slug", "a".repeat(81), "slug", "too-long"),
                Arguments.of("no title", "$.title", ABSENT, "title", "required"),
                Arguments.of("blank title", "$.title", "  ", "title", "required"),
                Arguments.of("title of 121 characters", "$.title", "a".repeat(121), "title", "too-long"),
                Arguments.of("summary of 301 characters", "$.summary", "a".repeat(301), "summary", "too-long"),
                Arguments.of("unknown area", "$.area", "MOBILE", "area", "invalid-format"),
                Arguments.of("area in lower case", "$.area", "backend", "area", "invalid-format"),
                Arguments.of("unknown icon", "$.icon", "ROCKET", "icon", "invalid-format"),
                Arguments.of("unknown tone", "$.tone", "BLUE", "tone", "invalid-format"),
                Arguments.of("tone in the web's words", "$.tone", "salvia", "tone", "invalid-format"),
                Arguments.of("31 about paragraphs", "$.about", Collections.nCopies(31, "Parágrafo."), "about",
                        "too-long"),
                Arguments.of("blank about paragraph", "$.about[0]", " ", "about[0]", "required"),
                Arguments.of("learn item of 1001 characters", "$.learn[0]", tooLongItem, "learn[0]", "too-long"),
                Arguments.of("null audience item", "$.audience[0]", null, "audience[0]", "required"),
                Arguments.of("empty planned topic", "$.plannedTopics[0]", "", "plannedTopics[0]", "required"),
                Arguments.of("31 planned topics", "$.plannedTopics", Collections.nCopies(31, "Tópico."),
                        "plannedTopics", "too-long"),
                Arguments.of("FAQ question missing", "$.faq[0].question", ABSENT, "faq[0].question", "required"),
                Arguments.of("FAQ answer of 1001 characters", "$.faq[0].answer", tooLongItem, "faq[0].answer",
                        "too-long"),
                Arguments.of("null FAQ entry", "$.faq[0]", null, "faq[0]", "required"),
                Arguments.of("31 FAQ entries", "$.faq", Collections.nCopies(31,
                        Map.of("question", "Pergunta?", "answer", "Resposta.")), "faq", "too-long"),
                Arguments.of("fractional price", "$.priceCents", 49700.5, "priceCents", "invalid-format"),
                Arguments.of("price of zero", "$.priceCents", 0, "priceCents", "out-of-range"),
                Arguments.of("negative price", "$.priceCents", -49700, "priceCents", "out-of-range"),
                Arguments.of("negative Pix discount", "$.pixDiscountPercent", -1, "pixDiscountPercent",
                        "out-of-range"),
                Arguments.of("Pix discount of 100%", "$.pixDiscountPercent", 100, "pixDiscountPercent",
                        "out-of-range"),
                Arguments.of("no installments", "$.maxInstallments", 0, "maxInstallments", "out-of-range"),
                Arguments.of("13 installments", "$.maxInstallments", 13, "maxInstallments", "out-of-range"));
    }

    @Test
    void acceptsEveryFieldAtItsLimits() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        List<String> longestList = Collections.nCopies(30, "é".repeat(1000));
        String document = JsonPath.parse(fullDocument(slug))
                .set("$.summary", "é".repeat(300))
                .set("$.about", longestList)
                .set("$.learn", longestList)
                .set("$.audience", longestList)
                .set("$.plannedTopics", longestList)
                .set("$.faq", Collections.nCopies(30,
                        Map.of("question", "é".repeat(1000), "answer", "é".repeat(1000))))
                .set("$.priceCents", 12)
                .set("$.pixDiscountPercent", 99)
                .set("$.maxInstallments", 12)
                .jsonString();

        assertThat(put(id, document)).hasStatusOk();
        assertThat(get(id)).bodyJson().isLenientlyEqualTo(document);
    }

    @Test
    void acceptsASinglePaymentWithoutPixDiscount() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        String document = JsonPath.parse(fullDocument(slug))
                .set("$.priceCents", 1)
                .set("$.pixDiscountPercent", 0)
                .set("$.maxInstallments", 1)
                .jsonString();

        assertThat(put(id, document)).hasStatusOk().bodyJson().isLenientlyEqualTo(document);
    }

    @ParameterizedTest
    @CsvSource({"49700.5, invalid-format", "4970000000000, out-of-range"})
    void refusesAPriceThatIsNotAWholeNumberOfCentsAndChangesNothing(String price, String code) {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, fullDocument(slug))).hasStatusOk();
        String before = body(get(id));

        MvcTestResult result = put(id, fullDocument(slug).replace("\"priceCents\": 49700", "\"priceCents\": " + price));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "priceCents", "code": "%s"}]
                        }""".formatted(code));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentsShortOfOneField")
    void listsExactlyWhatEachNextStateIsMissing(String description, String path, Object value, String comingSoon,
                                                String onSale) {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        DocumentContext document = JsonPath.parse(fullDocument(slug));

        assertThat(put(id, (value == ABSENT ? document.delete(path) : document.set(path, value)).jsonString()))
                .hasStatusOk();

        assertThat(get(id)).bodyJson().extractingPath("$.readiness").isEqualTo(JsonPath.parse("""
                {"comingSoon": %s, "onSale": %s}""".formatted(comingSoon, onSale)).json());
    }

    static Stream<Arguments> documentsShortOfOneField() {
        return Stream.of(
                Arguments.of("no summary", "$.summary", ABSENT, "[\"summary\"]", "[\"summary\", \"freeLessonId\"]"),
                Arguments.of("blank summary", "$.summary", " ", "[\"summary\"]", "[\"summary\", \"freeLessonId\"]"),
                Arguments.of("no area", "$.area", ABSENT, "[\"area\"]", "[\"area\", \"freeLessonId\"]"),
                Arguments.of("no icon", "$.icon", ABSENT, "[\"icon\"]", "[\"icon\", \"freeLessonId\"]"),
                Arguments.of("no tone", "$.tone", ABSENT, "[\"tone\"]", "[\"tone\", \"freeLessonId\"]"),
                Arguments.of("no about", "$.about", List.of(), "[\"about\"]", "[\"about\", \"freeLessonId\"]"),
                Arguments.of("no learn", "$.learn", ABSENT, "[\"learn\"]", "[\"learn\", \"freeLessonId\"]"),
                Arguments.of("no audience", "$.audience", List.of(), "[\"audience\"]",
                        "[\"audience\", \"freeLessonId\"]"),
                Arguments.of("no Planned topics", "$.plannedTopics", List.of(), "[\"plannedTopics\"]",
                        "[\"freeLessonId\"]"),
                Arguments.of("no price", "$.priceCents", ABSENT, "[]", "[\"priceCents\", \"freeLessonId\"]"),
                Arguments.of("no max installments", "$.maxInstallments", ABSENT, "[]",
                        "[\"maxInstallments\", \"freeLessonId\"]"),
                Arguments.of("no Pix discount", "$.pixDiscountPercent", ABSENT, "[]", "[\"freeLessonId\"]"),
                Arguments.of("no FAQ", "$.faq", List.of(), "[]", "[\"freeLessonId\"]"));
    }

    @Test
    void shrinksTheReadinessAsTheFieldsAreFilled() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        DocumentContext document = JsonPath.parse("""
                {"slug": "%s", "title": "Backend com Node.js"}""".formatted(slug));
        List<Map.Entry<String, Object>> fields = List.of(
                Map.entry("summary", "Construa APIs REST."),
                Map.entry("area", "BACKEND"),
                Map.entry("icon", "SERVER"),
                Map.entry("tone", "CORAL"),
                Map.entry("about", List.of("Por quê.")),
                Map.entry("learn", List.of("Rotas.")),
                Map.entry("audience", List.of("Devs.")),
                Map.entry("plannedTopics", List.of("APIs.")));

        for (int filled = 1; filled <= fields.size(); filled++) {
            document.put("$", fields.get(filled - 1).getKey(), fields.get(filled - 1).getValue());
            assertThat(put(id, document.jsonString())).hasStatusOk();

            List<String> stillMissing = fields.subList(filled, fields.size()).stream().map(Map.Entry::getKey).toList();
            assertThat(get(id)).bodyJson().extractingPath("$.readiness.comingSoon").isEqualTo(stillMissing);
        }
    }

    @Test
    void readsAComingSoonCourseWithWhenItWasAnnouncedAndWhatItNeedsToGoOnSale() {
        long id = new StoredCourses(jdbc).insertComingSoon(newSlug(), Instant.parse("2026-09-01T12:00:00Z"));

        assertThat(get(id)).hasStatusOk().bodyJson()
                .doesNotHavePath("$.onSaleAt")
                .doesNotHavePath("$.readiness.comingSoon")
                .isLenientlyEqualTo("""
                        {
                          "status": "COMING_SOON",
                          "comingSoonAt": "2026-09-01T12:00:00Z",
                          "readiness": {"onSale": ["priceCents", "maxInstallments", "freeLessonId"]},
                          "waitlistCount": 0
                        }""");
    }

    @Test
    void readsAnOnSaleCourseWithWhenItLaunchedItsFreeLessonAndNoReadiness() {
        clock.set(Instant.parse("2026-09-15T09:30:00Z"));
        long id = courses().onSale(newSlug());

        assertThat(get(id)).hasStatusOk().bodyJson()
                .doesNotHavePath("$.comingSoonAt")
                .doesNotHavePath("$.readiness")
                .doesNotHavePath("$.waitlistCount")
                .isLenientlyEqualTo("""
                        {"status": "ON_SALE", "onSaleAt": "2026-09-15T09:30:00Z", "freeLessonId": %d}"""
                        .formatted(freeLessonOf(id)));
    }

    @Test
    void announcesADraftThatHasEverythingComingSoonNeedsAndAnswersARepeatWithoutChangingIt() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, fullDocument(slug))).hasStatusOk();
        clock.set(Instant.parse("2026-10-04T15:00:00.123456789Z"));

        MvcTestResult announced = changeStatus(id, "COMING_SOON");

        assertThat(announced).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson()
                .doesNotHavePath("$.onSaleAt")
                .doesNotHavePath("$.readiness.comingSoon")
                .isLenientlyEqualTo("""
                        {
                          "id": %d,
                          "slug": "%s",
                          "status": "COMING_SOON",
                          "comingSoonAt": "2026-10-04T15:00:00.123456Z",
                          "readiness": {"onSale": ["freeLessonId"]}
                        }""".formatted(id, slug));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(body(announced));

        clock.set(Instant.parse("2026-10-04T16:00:00Z"));

        assertThat(changeStatus(id, "COMING_SOON")).hasStatusOk().bodyJson().isStrictlyEqualTo(body(announced));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(body(announced));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coursesShortOfTheirNextState")
    void refusesAMoveTheCourseIsNotReadyForListingEveryMissingFieldAndChangesNothing(String description,
                                                                                     String document,
                                                                                     String status,
                                                                                     List<String> missing) {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, document.formatted(slug))).hasStatusOk();
        String before = body(get(id));

        MvcTestResult result = changeStatus(id, status);

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/course-requirements-unmet",
                          "title": "Course requirements unmet",
                          "status": 409,
                          "detail": "A Course must have every field its state needs; missing lists those it lacks.",
                          "instance": "/v1/admin/courses/%d/status",
                          "timestamp": "%s",
                          "missing": %s
                        }""".formatted(id, clock.instant(), JsonPath.parse(missing).jsonString()));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    static Stream<Arguments> coursesShortOfTheirNextState() {
        String bare = """
                {"slug": "%s", "title": "Backend com Node.js"}""";
        String withoutPlannedTopics = JsonPath.parse(fullDocument("%s")).set("$.plannedTopics", List.of()).jsonString();
        return Stream.of(
                Arguments.of("a bare Draft to Coming soon", bare, "COMING_SOON",
                        List.of("summary", "area", "icon", "tone", "about", "learn", "audience", "plannedTopics")),
                Arguments.of("a Draft without Planned topics to Coming soon", withoutPlannedTopics, "COMING_SOON",
                        List.of("plannedTopics")),
                Arguments.of("a bare Draft to On sale", bare, "ON_SALE",
                        List.of("summary", "area", "icon", "tone", "about", "learn", "audience", "priceCents",
                                "maxInstallments", "freeLessonId")),
                Arguments.of("a Draft without a Free lesson to On sale", fullDocument("%s"), "ON_SALE",
                        List.of("freeLessonId")));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "no status            | {}                          | required",
            "null status          | {\"status\": null}          | required",
            "unknown status       | {\"status\": \"RETIRED\"}     | invalid-format",
            "status in lower case | {\"status\": \"coming_soon\"} | invalid-format"})
    void refusesAMoveWithoutAKnownStatusAndChangesNothing(String description, String request, String code) {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, fullDocument(slug))).hasStatusOk();
        String before = body(get(id));

        MvcTestResult result = mvc.put().uri("/v1/admin/courses/" + id + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "instance": "/v1/admin/courses/%d/status",
                          "errors": [{"field": "status", "code": "%s"}]
                        }""".formatted(id, code));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void refusesToMoveAComingSoonCourseBackToDraftAndChangesNothing() {
        long id = announcedCourse(newSlug());
        String before = body(get(id));

        assertCannotMoveBack(changeStatus(id, "DRAFT"), id);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMING_SOON", "DRAFT"})
    void refusesToMoveAnOnSaleCourseBackAndChangesNothing(String status) {
        long id = courses().launchedAfterAnnouncement(newSlug());
        String before = body(get(id));

        assertCannotMoveBack(changeStatus(id, status), id);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void answersADraftMovedToDraftWithoutChangingIt() {
        long id = idOf(create(newSlug(), "Backend"));
        String before = body(get(id));

        assertThat(changeStatus(id, "DRAFT")).hasStatusOk().bodyJson().isStrictlyEqualTo(before);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void answersAnOnSaleCourseMovedToOnSaleWithoutChangingIt() {
        long id = courses().onSale(newSlug());
        String before = body(get(id));

        assertThat(changeStatus(id, "ON_SALE")).hasStatusOk().bodyJson().isStrictlyEqualTo(before);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void takesAnEditThatKeepsAComingSoonCourseFitForItsState() {
        String slug = newSlug();
        long id = announcedCourse(slug);
        String document = JsonPath.parse(fullDocument(slug))
                .set("$.title", "Backend com Node.js e TypeScript")
                .set("$.plannedTopics", List.of("Fundamentos de APIs.", "Autenticação."))
                .delete("$.faq")
                .delete("$.priceCents")
                .delete("$.pixDiscountPercent")
                .delete("$.maxInstallments")
                .jsonString();

        assertThat(put(id, document)).hasStatusOk().bodyJson()
                .doesNotHavePath("$.priceCents")
                .isLenientlyEqualTo(document)
                .isLenientlyEqualTo("""
                        {"status": "COMING_SOON", "faq": []}""");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editsLeavingComingSoonShort")
    void refusesAnEditThatLeavesAComingSoonCourseShortOfItsStateListingWhatIsMissing(
            String description, UnaryOperator<DocumentContext> edit, List<String> missing) {
        String slug = newSlug();
        long id = announcedCourse(slug);
        String before = body(get(id));

        MvcTestResult result = put(id, edit.apply(JsonPath.parse(fullDocument(slug))
                .set("$.title", "Backend com Node.js e Java")).jsonString());

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/course-requirements-unmet",
                          "title": "Course requirements unmet",
                          "status": 409,
                          "detail": "A Course must have every field its state needs; missing lists those it lacks.",
                          "instance": "/v1/admin/courses/%d",
                          "timestamp": "%s",
                          "missing": %s
                        }""".formatted(id, clock.instant(), JsonPath.parse(missing).jsonString()));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    static Stream<Arguments> editsLeavingComingSoonShort() {
        return Stream.of(
                Arguments.of("blank summary", edit(document -> document.set("$.summary", " ")), List.of("summary")),
                Arguments.of("no area", edit(document -> document.delete("$.area")), List.of("area")),
                Arguments.of("null icon", edit(document -> document.set("$.icon", null)), List.of("icon")),
                Arguments.of("no tone", edit(document -> document.delete("$.tone")), List.of("tone")),
                Arguments.of("no about", edit(document -> document.set("$.about", List.of())), List.of("about")),
                Arguments.of("no learn", edit(document -> document.delete("$.learn")), List.of("learn")),
                Arguments.of("no audience", edit(document -> document.set("$.audience", List.of())),
                        List.of("audience")),
                Arguments.of("no Planned topics", edit(document -> document.set("$.plannedTopics", List.of())),
                        List.of("plannedTopics")),
                Arguments.of("nothing but the slug and title", edit(document -> JsonPath.parse(Map.of(
                                "slug", document.read("$.slug"), "title", document.read("$.title")))),
                        List.of("summary", "area", "icon", "tone", "about", "learn", "audience", "plannedTopics")));
    }

    private static UnaryOperator<DocumentContext> edit(UnaryOperator<DocumentContext> edit) {
        return edit;
    }

    @Test
    void setsAPublishedLessonOfTheCourseAsItsFreeLessonAndReadsItBack() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        long lesson = courses().addPublishedLesson(courses().addModule(id, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");

        MvcTestResult result = put(id, fullDocument(slug, lesson));

        assertThat(result).hasStatusOk().bodyJson()
                .isLenientlyEqualTo(fullDocument(slug, lesson))
                .isLenientlyEqualTo("""
                        {"status": "DRAFT", "freeLessonId": %d, "readiness": {"comingSoon": [], "onSale": []}}"""
                        .formatted(lesson));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(body(result));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lessonsThatCannotBeFree")
    void refusesAFreeLessonThatIsNotAPublishedLessonOfTheCourseAndChangesNothing(
            String description, BiFunction<AdminCourses, Long, Long> lessonOf) {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        long lesson = lessonOf.apply(courses(), courses().addModule(id, "Fundamentos"));
        assertThat(put(id, fullDocument(slug))).hasStatusOk();
        String before = body(get(id));

        MvcTestResult result = put(id, fullDocument(slug, lesson));

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/free-lesson-ineligible",
                          "title": "Free lesson ineligible",
                          "status": 409,
                          "detail": "freeLessonId must name a published Lesson of this Course.",
                          "instance": "/v1/admin/courses/%d",
                          "timestamp": "%s"
                        }""".formatted(id, clock.instant()));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    static Stream<Arguments> lessonsThatCannotBeFree() {
        return Stream.of(
                Arguments.of("an unpublished Lesson of the Course, with its video", lessonOf((courses, module) -> {
                    long lesson = courses.addLesson(module, "O que é uma API", "o-que-e-uma-api");
                    courses.linkVideo(lesson, "three-seconds.mp4");
                    return lesson;
                })),
                Arguments.of("a published Lesson of another Course", lessonOf((courses, module) ->
                        courses.addPublishedLesson(courses.addModule(courses.draft(newSlug()), "Fundamentos"),
                                "O que é uma API", "o-que-e-uma-api", "three-seconds.mp4"))),
                Arguments.of("an id no Lesson has", lessonOf((courses, module) -> 999999999999999999L)));
    }

    private static BiFunction<AdminCourses, Long, Long> lessonOf(BiFunction<AdminCourses, Long, Long> lessonOf) {
        return lessonOf;
    }

    @Test
    void launchesADraftStraightToOnSaleAndAnswersARepeatWithoutChangingIt() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        long lesson = courses().addPublishedLesson(courses().addModule(id, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        assertThat(put(id, fullDocument(slug, lesson))).hasStatusOk();
        clock.set(Instant.parse("2026-10-04T15:00:00.123456789Z"));

        MvcTestResult launched = changeStatus(id, "ON_SALE");

        assertThat(launched).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson()
                .doesNotHavePath("$.comingSoonAt")
                .doesNotHavePath("$.readiness")
                .isLenientlyEqualTo(fullDocument(slug, lesson))
                .isLenientlyEqualTo("""
                        {"id": %d, "status": "ON_SALE", "onSaleAt": "2026-10-04T15:00:00.123456Z"}"""
                        .formatted(id));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(body(launched));

        clock.set(Instant.parse("2026-10-04T16:00:00Z"));

        assertThat(changeStatus(id, "ON_SALE")).hasStatusOk().bodyJson().isStrictlyEqualTo(body(launched));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(body(launched));
    }

    @Test
    void launchesAComingSoonCourseKeepingWhenItWasAnnounced() {
        clock.set(Instant.parse("2026-10-01T12:00:00Z"));
        String slug = newSlug();
        long id = announcedCourse(slug);
        long lesson = courses().addPublishedLesson(courses().addModule(id, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        assertThat(put(id, fullDocument(slug, lesson))).hasStatusOk();
        clock.set(Instant.parse("2026-10-04T15:00:00Z"));

        assertThat(changeStatus(id, "ON_SALE")).hasStatusOk().bodyJson()
                .doesNotHavePath("$.readiness")
                .isLenientlyEqualTo("""
                        {
                          "status": "ON_SALE",
                          "comingSoonAt": "2026-10-01T12:00:00Z",
                          "onSaleAt": "2026-10-04T15:00:00Z",
                          "freeLessonId": %d
                        }""".formatted(lesson));
    }

    @Test
    void refusesToLaunchAComingSoonCourseShortOfOnSaleListingEveryMissingFieldAndChangesNothing() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, JsonPath.parse(fullDocument(slug)).delete("$.priceCents").delete("$.maxInstallments")
                .jsonString())).hasStatusOk();
        assertThat(changeStatus(id, "COMING_SOON")).hasStatusOk();
        String before = body(get(id));

        assertRequirementsUnmet(changeStatus(id, "ON_SALE"), "/v1/admin/courses/%d/status".formatted(id),
                List.of("priceCents", "maxInstallments", "freeLessonId"));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void takesANewPriceAndAnotherFreeLessonWhileOnSale() {
        String slug = newSlug();
        long id = courses().onSale(slug);
        long module = courses().addModule(id, "Rotas e respostas");
        long lesson = courses().addPublishedLesson(module, "Rotas no Express", "rotas-no-express", "five-seconds.mp4");
        String document = JsonPath.parse(fullDocument(slug, lesson))
                .set("$.priceCents", 59700)
                .set("$.pixDiscountPercent", 15)
                .set("$.maxInstallments", 12)
                .jsonString();

        assertThat(put(id, document)).hasStatusOk().bodyJson()
                .isLenientlyEqualTo(document)
                .isLenientlyEqualTo("""
                        {"status": "ON_SALE"}""");
        assertThat(get(id)).bodyJson().isLenientlyEqualTo(document);
    }

    @Test
    void takesAnEditThatKeepsAnOnSaleCourseFitWithoutItsPlannedTopics() {
        String slug = newSlug();
        long id = courses().onSale(slug);
        String document = JsonPath.parse(fullDocument(slug, freeLessonOf(id)))
                .set("$.plannedTopics", List.of())
                .delete("$.pixDiscountPercent")
                .jsonString();

        assertThat(put(id, document)).hasStatusOk().bodyJson()
                .doesNotHavePath("$.pixDiscountPercent")
                .isLenientlyEqualTo(document);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editsLeavingOnSaleShort")
    void refusesAnEditThatLeavesAnOnSaleCourseShortOfItsStateListingWhatIsMissing(
            String description, UnaryOperator<DocumentContext> edit, List<String> missing) {
        String slug = newSlug();
        long id = courses().onSale(slug);
        String before = body(get(id));

        MvcTestResult result = put(id, edit.apply(JsonPath.parse(fullDocument(slug, freeLessonOf(id)))
                .set("$.title", "Backend com Node.js e Java")).jsonString());

        assertRequirementsUnmet(result, "/v1/admin/courses/" + id, missing);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    static Stream<Arguments> editsLeavingOnSaleShort() {
        return Stream.of(
                Arguments.of("no Free lesson", edit(document -> document.delete("$.freeLessonId")),
                        List.of("freeLessonId")),
                Arguments.of("null Free lesson", edit(document -> document.set("$.freeLessonId", null)),
                        List.of("freeLessonId")),
                Arguments.of("no price", edit(document -> document.delete("$.priceCents")), List.of("priceCents")),
                Arguments.of("no max installments", edit(document -> document.delete("$.maxInstallments")),
                        List.of("maxInstallments")),
                Arguments.of("no about", edit(document -> document.set("$.about", List.of())), List.of("about")),
                Arguments.of("nothing but the slug and title", edit(document -> JsonPath.parse(Map.of(
                                "slug", document.read("$.slug"), "title", document.read("$.title")))),
                        List.of("summary", "area", "icon", "tone", "about", "learn", "audience", "priceCents",
                                "maxInstallments", "freeLessonId")));
    }

    @Test
    void refusesAPriceNotDivisibleByTheMaximumInstallmentsWhileOnSaleAndChangesNothing() {
        String slug = newSlug();
        long id = courses().onSale(slug);
        String before = body(get(id));

        MvcTestResult result = put(id, JsonPath.parse(fullDocument(slug, freeLessonOf(id)))
                .set("$.priceCents", 49701)
                .jsonString());

        assertThat(result).hasStatus(HttpStatus.CONFLICT).bodyJson().extractingPath("$.type")
                .isEqualTo("https://aulaflix.com.br/problems/price-not-divisible-by-installments");
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void deletesADraftWithItsPublishedFreeLessonAndItsVideo() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        long lesson = courses().addPublishedLesson(courses().addModule(id, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        assertThat(put(id, fullDocument(slug, lesson))).hasStatusOk();

        assertThat(delete(id)).hasStatus(HttpStatus.NO_CONTENT);

        assertCourseNotFound(get(id), "/v1/admin/courses/" + id);
        assertThat(storedVideos.keysOf(lesson)).isEmpty();
    }

    @Test
    void refusesToChangeTheSlugOfAComingSoonCourseAndChangesNothing() {
        long id = announcedCourse(newSlug());
        String before = body(get(id));

        assertSlugFrozen(put(id, fullDocument(newSlug())), id);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void refusesToChangeTheSlugOfAnOnSaleCourseAndChangesNothing() {
        long id = courses().onSale(newSlug());
        String before = body(get(id));

        assertSlugFrozen(put(id, fullDocument(newSlug(), freeLessonOf(id))), id);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void refusesToDeleteACourseOnceItWentComingSoon() {
        long id = announcedCourse(newSlug());

        assertThat(delete(id)).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/course-not-draft");
        assertThat(get(id)).hasStatusOk().bodyJson().extractingPath("$.status").isEqualTo("COMING_SOON");
    }

    @Test
    void refusesAPriceNotDivisibleByTheMaximumInstallmentsAndChangesNothing() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, fullDocument(slug))).hasStatusOk();
        String before = body(get(id));

        MvcTestResult result = put(id, JsonPath.parse(fullDocument(slug))
                .set("$.title", "Backend com Node.js e TypeScript")
                .set("$.priceCents", 49700)
                .set("$.maxInstallments", 3)
                .jsonString());

        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/price-not-divisible-by-installments",
                          "title": "Price not divisible by installments",
                          "status": 409,
                          "detail": "%s",
                          "instance": "/v1/admin/courses/%d",
                          "timestamp": "%s"
                        }""".formatted("priceCents must be divisible by maxInstallments, so that every installment "
                        + "is exact.", id, clock.instant()));
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"$.priceCents", "$.maxInstallments"})
    void takesAPriceOrMaximumInstallmentsAloneWhateverTheOtherWouldNeed(String absent) {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));

        MvcTestResult result = put(id, JsonPath.parse(fullDocument(slug))
                .set("$.priceCents", 49701)
                .set("$.maxInstallments", 7)
                .delete(absent)
                .jsonString());

        assertThat(result).hasStatusOk().bodyJson().doesNotHavePath(absent);
    }

    @Test
    void refusesASlugAnotherCourseHas() {
        String slug = newSlug();
        assertThat(create(slug, "Backend com Node.js")).hasStatus(HttpStatus.CREATED);

        assertSlugTaken(create(slug, "Backend com Java"), "/v1/admin/courses");
    }

    @Test
    void refusesACourseWithoutSlugOrTitle() {
        MvcTestResult result = mvc.post().uri("/v1/admin/courses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "One or more fields are invalid.",
                          "instance": "/v1/admin/courses",
                          "timestamp": "%s",
                          "errors": [
                            {"field": "slug", "code": "required"},
                            {"field": "title", "code": "required"}
                          ]
                        }""".formatted(clock.instant()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidNewCourses")
    void refusesAnInvalidSlugOrTitleWithOneCode(String description, String slug, String title, String field,
                                                String code) {
        assertThat(create(slug, title)).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "%s", "code": "%s"}]
                        }""".formatted(field, code));
    }

    static Stream<Arguments> invalidNewCourses() {
        String title = "Backend com Node.js";
        return Stream.of(
                Arguments.of("blank slug", "   ", title, "slug", "required"),
                Arguments.of("slug with upper case", "Backend-com-node", title, "slug", "invalid-format"),
                Arguments.of("slug with an accent", "programacao-em-céu", title, "slug", "invalid-format"),
                Arguments.of("slug with a space", "backend com-node", title, "slug", "invalid-format"),
                Arguments.of("slug with two hyphens in a row", "backend--node", title, "slug", "invalid-format"),
                Arguments.of("slug starting with a hyphen", "-backend", title, "slug", "invalid-format"),
                Arguments.of("slug ending with a hyphen", "backend-", title, "slug", "invalid-format"),
                Arguments.of("slug of 81 characters", "a".repeat(81), title, "slug", "too-long"),
                Arguments.of("slug of 81 upper-case characters", "A".repeat(81), title, "slug", "too-long"),
                Arguments.of("blank title", "backend-" + UUID.randomUUID(), " ", "title", "required"),
                Arguments.of("title of 121 characters", "backend-" + UUID.randomUUID(), "a".repeat(121), "title",
                        "too-long"));
    }

    @Test
    void acceptsTheLongestSlugAndTitle() {
        String slug = UUID.randomUUID().toString() + "-" + "a".repeat(43);

        assertThat(create(slug, "á".repeat(120))).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.slug").isEqualTo(slug);
    }

    @Test
    void refusesToMoveADraftOntoTheSlugOfAnotherCourse() {
        String taken = newSlug();
        assertThat(create(taken, "Backend com Node.js")).hasStatus(HttpStatus.CREATED);
        String slug = newSlug();
        long id = idOf(create(slug, "Backend com Java"));
        String before = body(get(id));

        assertSlugTaken(put(id, fullDocument(taken)), "/v1/admin/courses/" + id);
        assertThat(get(id)).bodyJson().isStrictlyEqualTo(before);
    }

    @Test
    void deletesADraftWhichIsThenNotFound() {
        String slug = newSlug();
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, fullDocument(slug))).hasStatusOk();

        assertThat(delete(id)).hasStatus(HttpStatus.NO_CONTENT).body().isEmpty();

        assertCourseNotFound(get(id), "/v1/admin/courses/" + id);
        assertCourseNotFound(delete(id), "/v1/admin/courses/" + id);
        assertThat(create(slug, "Backend")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void refusesToDeleteACourseThatIsNoLongerADraft() {
        long id = new StoredCourses(jdbc).insertComingSoon(newSlug(), Instant.parse("2026-09-01T12:00:00Z"));
        String before = body(get(id));

        assertThat(delete(id)).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/course-not-draft",
                          "title": "Course not a Draft",
                          "status": 409,
                          "detail": "Only a Draft Course can be deleted.",
                          "instance": "/v1/admin/courses/%d",
                          "timestamp": "%s"
                        }""".formatted(id, clock.instant()));
        assertThat(get(id)).hasStatusOk().bodyJson().isStrictlyEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999999999", "0", "-1", "007", "1.5", "abc", "99999999999999999999"})
    void answersAnIdOfAnyShapeLikeAnUnknownOne(String courseId) {
        String path = "/v1/admin/courses/" + courseId;
        String bearer = "Bearer " + token;

        assertCourseNotFound(mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange(), path);
        assertCourseNotFound(mvc.put().uri(path).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(fullDocument(newSlug())).exchange(), path);
        assertCourseNotFound(mvc.delete().uri(path).header(HttpHeaders.AUTHORIZATION, bearer).exchange(), path);
        assertCourseNotFound(mvc.put().uri(path + "/status").header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"COMING_SOON\"}").exchange(),
                path + "/status");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void refusesEveryEndpointWithoutASession(HttpMethod method, String path) {
        long id = idOf(create(newSlug(), "Backend"));
        String before = body(get(id));

        MvcTestResult result = mvc.perform(MockMvcRequestBuilders.request(method, path.replace("{id}",
                        Long.toString(id)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(fullDocument(newSlug())));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/unauthenticated");
        assertThat(get(id)).hasStatusOk().bodyJson().isStrictlyEqualTo(before);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void refusesEveryEndpointOnceTheAdminSignedOut(HttpMethod method, String path) {
        long id = idOf(create(newSlug(), "Backend"));
        String signedOut = token;
        new AdminApi(mvc).signOut(signedOut);

        MvcTestResult result = mvc.perform(MockMvcRequestBuilders.request(method, path.replace("{id}",
                        Long.toString(id)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signedOut)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fullDocument(newSlug())));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/unauthenticated");
    }

    static Stream<Arguments> everyEndpoint() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/v1/admin/courses"),
                Arguments.of(HttpMethod.GET, "/v1/admin/courses"),
                Arguments.of(HttpMethod.GET, "/v1/admin/courses/{id}"),
                Arguments.of(HttpMethod.PUT, "/v1/admin/courses/{id}"),
                Arguments.of(HttpMethod.DELETE, "/v1/admin/courses/{id}"),
                Arguments.of(HttpMethod.PUT, "/v1/admin/courses/{id}/status"));
    }

    private void assertSlugTaken(MvcTestResult result, String path) {
        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/slug-taken",
                          "title": "Slug taken",
                          "status": 409,
                          "detail": "Another Course already has this slug.",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(path, clock.instant()));
    }

    private void assertSlugFrozen(MvcTestResult result, long id) {
        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/slug-frozen",
                          "title": "Slug frozen",
                          "status": 409,
                          "detail": "The slug changes only while the Course is a Draft.",
                          "instance": "/v1/admin/courses/%d",
                          "timestamp": "%s"
                        }""".formatted(id, clock.instant()));
    }

    private void assertCannotMoveBack(MvcTestResult result, long id) {
        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/course-cannot-move-back",
                          "title": "Course cannot move back",
                          "status": 409,
                          "detail": "A Course moves forward only: from Draft to Coming soon to On sale.",
                          "instance": "/v1/admin/courses/%d/status",
                          "timestamp": "%s"
                        }""".formatted(id, clock.instant()));
    }

    private void assertRequirementsUnmet(MvcTestResult result, String path, List<String> missing) {
        assertThat(result).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/course-requirements-unmet",
                          "title": "Course requirements unmet",
                          "status": 409,
                          "detail": "A Course must have every field its state needs; missing lists those it lacks.",
                          "instance": "%s",
                          "timestamp": "%s",
                          "missing": %s
                        }""".formatted(path, clock.instant(), JsonPath.parse(missing).jsonString()));
    }

    private void assertCourseNotFound(MvcTestResult result, String path) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/course-not-found",
                          "title": "Course not found",
                          "status": 404,
                          "detail": "The Course does not exist.",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(path, clock.instant()));
    }

    /** A document with every field set, ready to go Coming soon, and On sale but for its Free lesson. */
    private static String fullDocument(String slug) {
        return """
                {
                  "slug": "%s",
                  "title": "Backend com Node.js",
                  "summary": "Construa APIs REST com Node.js e TypeScript.",
                  "area": "BACKEND",
                  "icon": "SERVER",
                  "tone": "CORAL",
                  "about": ["Quase todo produto depende de um backend."],
                  "learn": ["Projetar rotas e respostas."],
                  "audience": ["Para devs frontend."],
                  "plannedTopics": ["Fundamentos de APIs."],
                  "faq": [{"question": "Preciso saber JavaScript?", "answer": "Sim, o básico."}],
                  "priceCents": 49700,
                  "pixDiscountPercent": 10,
                  "maxInstallments": 10
                }""".formatted(slug);
    }

    /** {@link #fullDocument} with the Free lesson set, ready to go On sale too. */
    private static String fullDocument(String slug, long freeLessonId) {
        return JsonPath.parse(fullDocument(slug)).put("$", "freeLessonId", freeLessonId).jsonString();
    }

    private AdminCourses courses() {
        return new AdminCourses(mvc, token, storedVideos);
    }

    private long freeLessonOf(long id) {
        return ((Number) JsonPath.read(body(get(id)), "$.freeLessonId")).longValue();
    }

    /** A Course moved to Coming soon through the API, from a document with every field set. */
    private long announcedCourse(String slug) {
        long id = idOf(create(slug, "Backend"));
        assertThat(put(id, fullDocument(slug))).hasStatusOk();
        assertThat(changeStatus(id, "COMING_SOON")).hasStatusOk();
        return id;
    }

    private MvcTestResult get(long id) {
        return mvc.get().uri("/v1/admin/courses/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private MvcTestResult delete(long id) {
        return mvc.delete().uri("/v1/admin/courses/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private MvcTestResult put(long id, String document) {
        return mvc.put().uri("/v1/admin/courses/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(document)
                .exchange();
    }

    private MvcTestResult changeStatus(long id, String status) {
        return mvc.put().uri("/v1/admin/courses/" + id + "/status")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status": "%s"}""".formatted(status))
                .exchange();
    }

    private MvcTestResult create(String slug, String title) {
        return mvc.post().uri("/v1/admin/courses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"slug": "%s", "title": "%s"}""".formatted(slug, title))
                .exchange();
    }

    private static Map<String, Object> listedItem(MvcTestResult list, long id) {
        List<Map<String, Object>> matches = JsonPath.read(body(list), "$.items[?(@.id == %d)]".formatted(id));
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private static long idOf(MvcTestResult result) {
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }

    private static String newSlug() {
        return "curso-" + UUID.randomUUID();
    }
}
