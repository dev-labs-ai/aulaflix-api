package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * The public catalog, as the BFF reads it. Every test shares one database, so each one looks only at the Courses it
 * made, and the Admin endpoints make them.
 */
class CourseControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    private BffApi bff;

    private AdminCourses courses;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos);
        bff = new BffApi(mvc);
    }

    @Test
    void listsComingSoonCoursesNewestAnnouncementFirstAndNeverADraft() {
        clock.set(Instant.parse("2026-10-04T12:00:00Z"));
        String olderSlug = newSlug();
        long older = courses.announced(olderSlug);
        clock.set(Instant.parse("2026-10-04T13:00:00Z"));
        long newer = courses.announced(newSlug());
        long draft = courses.draft(newSlug());

        MvcTestResult list = bff.get("/v1/courses").exchange();

        assertThat(list).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().extractingPath("$").asMap().containsOnlyKeys("items");
        assertThat(listedIdsAmong(list, List.of(older, newer, draft))).containsExactly(newer, older);
        assertThat(JsonPath.<List<String>>read(body(list), "$.items[*].status")).isNotEmpty()
                .isSubsetOf("COMING_SOON", "ON_SALE");
        assertThat(listedItem(list, older)).isEqualTo(JsonPath.parse("""
                {
                  "id": %d,
                  "slug": "%s",
                  "title": "Backend com Node.js",
                  "summary": "Construa APIs REST com Node.js e TypeScript.",
                  "area": "BACKEND",
                  "icon": "SERVER",
                  "tone": "CORAL",
                  "status": "COMING_SOON",
                  "plannedTopicCount": 2
                }""".formatted(older, olderSlug)).json());
    }

    @Test
    void listsOnSaleCoursesFirstNewestLaunchFirstThenComingSoonOnes() {
        clock.set(Instant.parse("2026-08-01T12:00:00Z"));
        String slug = newSlug();
        long launchedAfterAnnouncement = courses.announced(slug);
        clock.set(Instant.parse("2026-09-01T12:00:00Z"));
        long launchedFirst = courses.onSale(newSlug());
        clock.set(Instant.parse("2026-09-10T12:00:00Z"));
        long launchedStraightFromDraft = courses.onSale(newSlug());
        clock.set(Instant.parse("2026-09-20T12:00:00Z"));
        courses.launch(launchedAfterAnnouncement, slug);
        clock.set(Instant.parse("2026-10-04T12:00:00Z"));
        long comingSoon = courses.announced(newSlug());

        MvcTestResult list = bff.get("/v1/courses").exchange();

        assertThat(listedIdsAmong(list, List.of(comingSoon, launchedFirst, launchedStraightFromDraft,
                launchedAfterAnnouncement)))
                .containsExactly(launchedAfterAnnouncement, launchedStraightFromDraft, launchedFirst, comingSoon);
        assertThat(listedItem(list, launchedAfterAnnouncement)).containsEntry("status", "ON_SALE")
                .doesNotContainKey("plannedTopicCount");
    }

    @Test
    void listsAnOnSaleCourseWithItsPricingAndEveryLessonCountedEmBreveOnesIncluded() {
        String slug = newSlug();
        long id = courses.onSale(slug);
        courses.addModule(id, "Módulo vazio");
        courses.addLesson(courses.addModule(id, "Rotas e respostas"), "Rotas no Express", "rotas-no-express");

        MvcTestResult list = bff.get("/v1/courses").exchange();

        assertThat(listedItem(list, id)).isEqualTo(JsonPath.parse("""
                {
                  "id": %d,
                  "slug": "%s",
                  "title": "Backend com Node.js",
                  "summary": "Construa APIs REST com Node.js e TypeScript.",
                  "area": "BACKEND",
                  "icon": "SERVER",
                  "tone": "CORAL",
                  "status": "ON_SALE",
                  "pricing": {
                    "priceCents": 49700,
                    "pixDiscountPercent": 10,
                    "pixPriceCents": 44730,
                    "maxInstallments": 10,
                    "installmentCents": 4970
                  },
                  "lessonCount": 2
                }""".formatted(id, slug)).json());
    }

    @Test
    void pricesACourseWithoutAPixDiscountAtTheFullPriceOnPix() {
        String slug = newSlug();
        long id = courses.onSale(slug);
        long freeLesson = ((Number) JsonPath.read(body(bff.get("/v1/courses/" + slug).exchange()), "$.freeLessonId"))
                .longValue();
        courses.putDocument(id, JsonPath.parse(AdminCourses.fullDocument(slug, freeLesson))
                .delete("$.pixDiscountPercent")
                .set("$.priceCents", 39990)
                .set("$.maxInstallments", 3)
                .jsonString());

        assertThat(bff.get("/v1/courses/" + slug)).bodyJson().extractingPath("$.pricing").isEqualTo(Map.of(
                "priceCents", 39990, "pixDiscountPercent", 0, "pixPriceCents", 39990, "maxInstallments", 3,
                "installmentCents", 13330));
        assertThat(listedItem(bff.get("/v1/courses").exchange(), id)).extractingByKey("pricing")
                .isEqualTo(Map.of("priceCents", 39990, "pixDiscountPercent", 0, "pixPriceCents", 39990,
                        "maxInstallments", 3, "installmentCents", 13330));
    }

    @ParameterizedTest
    @ValueSource(strings = {"?area=BACKEND", "?status=ON_SALE", "?page=0&size=20", "?q", "?area="})
    void refusesAnyQueryParameterSoThatAFilterIsNeverSilentlyIgnored(String query) {
        MvcTestResult result = bff.get("/v1/courses" + query).exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "This endpoint takes no query parameters.",
                          "instance": "/v1/courses",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }

    @Test
    void showsAComingSoonCourseWithItsPlannedTopicsAndNoneOfItsModulesNorItsFreeLesson() {
        String slug = newSlug();
        long id = courses.announced(slug);
        long lesson = courses.addPublishedLesson(courses.addModule(id, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        courses.putDocument(id, AdminCourses.fullDocument(slug, lesson));

        MvcTestResult detail = bff.get("/v1/courses/" + slug).exchange();

        assertThat(detail).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson()
                .doesNotHavePath("$.pricing")
                .doesNotHavePath("$.lessonCount")
                .doesNotHavePath("$.modules")
                .doesNotHavePath("$.freeLessonId")
                .isStrictlyEqualTo("""
                        {
                          "id": %d,
                          "slug": "%s",
                          "title": "Backend com Node.js",
                          "summary": "Construa APIs REST com Node.js e TypeScript.",
                          "area": "BACKEND",
                          "icon": "SERVER",
                          "tone": "CORAL",
                          "status": "COMING_SOON",
                          "plannedTopicCount": 2,
                          "about": [
                            "Quase todo produto depende de um backend.",
                            "Este curso constrói uma API do zero."
                          ],
                          "learn": ["Projetar rotas e respostas."],
                          "audience": ["Para devs frontend."],
                          "faq": [{"question": "Preciso saber JavaScript?", "answer": "Sim, o básico."}],
                          "plannedTopics": ["Fundamentos de APIs.", "Autenticação."]
                        }""".formatted(id, slug));
    }

    @Test
    void showsAnOnSaleCourseWithItsFreeLessonAndSyllabusInsteadOfThePlannedTopicsItHadWhileComingSoon() {
        String slug = newSlug();
        long id = courses.announced(slug);
        Syllabus syllabus = syllabusOf(id);
        courses.putDocument(id, AdminCourses.fullDocument(slug, syllabus.rotasNoExpress()));
        courses.moveTo(id, "ON_SALE");

        MvcTestResult detail = bff.get("/v1/courses/" + slug).exchange();

        assertThat(detail).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "id": %d,
                          "slug": "%s",
                          "title": "Backend com Node.js",
                          "summary": "Construa APIs REST com Node.js e TypeScript.",
                          "area": "BACKEND",
                          "icon": "SERVER",
                          "tone": "CORAL",
                          "status": "ON_SALE",
                          "pricing": {
                            "priceCents": 49700,
                            "pixDiscountPercent": 10,
                            "pixPriceCents": 44730,
                            "maxInstallments": 10,
                            "installmentCents": 4970
                          },
                          "lessonCount": 3,
                          "about": [
                            "Quase todo produto depende de um backend.",
                            "Este curso constrói uma API do zero."
                          ],
                          "learn": ["Projetar rotas e respostas."],
                          "audience": ["Para devs frontend."],
                          "faq": [{"question": "Preciso saber JavaScript?", "answer": "Sim, o básico."}],
                          "freeLessonId": %d,
                          "modules": [
                            {
                              "number": 1,
                              "title": "Fundamentos",
                              "lessons": [
                                {
                                  "id": %d,
                                  "number": 1,
                                  "title": "O que é uma API",
                                  "published": true,
                                  "slug": "o-que-e-uma-api",
                                  "durationSeconds": 3
                                },
                                {"id": %d, "number": 2, "title": "HTTP na prática", "published": false}
                              ]
                            },
                            {
                              "number": 2,
                              "title": "Rotas e respostas",
                              "lessons": [
                                {
                                  "id": %d,
                                  "number": 3,
                                  "title": "Rotas no Express",
                                  "published": true,
                                  "slug": "rotas-no-express",
                                  "durationSeconds": 5
                                }
                              ]
                            }
                          ]
                        }""".formatted(id, slug, syllabus.rotasNoExpress(), syllabus.oQueEUmaApi(),
                        syllabus.httpNaPratica(), syllabus.rotasNoExpress()));
    }

    @Test
    void renumbersTheSyllabusOnceTheOutlineIsReordered() {
        String slug = newSlug();
        long id = courses.completeDraft(slug);
        Syllabus syllabus = syllabusOf(id);
        courses.putDocument(id, AdminCourses.fullDocument(slug, syllabus.oQueEUmaApi()));
        courses.moveTo(id, "ON_SALE");

        courses.putOutline(id, """
                [
                  {"moduleId": %d, "lessonIds": []},
                  {"moduleId": %d, "lessonIds": [%d, %d]},
                  {"moduleId": %d, "lessonIds": [%d]}
                ]""".formatted(syllabus.fundamentos(), syllabus.rotas(), syllabus.rotasNoExpress(),
                syllabus.oQueEUmaApi(), syllabus.vazio(), syllabus.httpNaPratica()));

        MvcTestResult detail = bff.get("/v1/courses/" + slug).exchange();
        assertThat(detail).bodyJson().extractingPath("$.modules").isEqualTo(JsonPath.parse("""
                [
                  {
                    "number": 1,
                    "title": "Rotas e respostas",
                    "lessons": [
                      {
                        "id": %d,
                        "number": 1,
                        "title": "Rotas no Express",
                        "published": true,
                        "slug": "rotas-no-express",
                        "durationSeconds": 5
                      },
                      {
                        "id": %d,
                        "number": 2,
                        "title": "O que é uma API",
                        "published": true,
                        "slug": "o-que-e-uma-api",
                        "durationSeconds": 3
                      }
                    ]
                  },
                  {
                    "number": 2,
                    "title": "Módulo vazio",
                    "lessons": [{"id": %d, "number": 3, "title": "HTTP na prática", "published": false}]
                  }
                ]""".formatted(syllabus.rotasNoExpress(), syllabus.oQueEUmaApi(), syllabus.httpNaPratica()))
                .json());
        assertThat(listedItem(bff.get("/v1/courses").exchange(), id)).containsEntry("lessonCount", 3);
    }

    @Test
    void answersADraftByteForByteLikeASlugNoCourseHas() {
        String slug = newSlug();
        long draft = courses.completeDraft(slug);

        MvcTestResult whileADraft = bff.get("/v1/courses/" + slug).exchange();
        courses.delete(draft);
        MvcTestResult onceUnknown = bff.get("/v1/courses/" + slug).exchange();

        assertCourseNotFound(whileADraft, "/v1/courses/" + slug);
        assertThat(whileADraft.getResponse().getContentAsByteArray())
                .isEqualTo(onceUnknown.getResponse().getContentAsByteArray());
        assertThat(headersOf(whileADraft)).isEqualTo(headersOf(onceUnknown));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Backend-Com-Node", "curso_com_sublinhado", "123", "backend--node"})
    void answersASlugOfAnyShapeLikeOneNoCourseHas(String slug) {
        assertCourseNotFound(bff.get("/v1/courses/" + slug).exchange(), "/v1/courses/" + slug);
    }

    /**
     * The outline of a Syllabus: "Fundamentos" with "O que é uma API", published with a three-second video, then "HTTP
     * na prática", "Em breve" though its video is linked; an empty Module; and "Rotas e respostas" with "Rotas no
     * Express", published with a five-second video.
     */
    private Syllabus syllabusOf(long courseId) {
        long fundamentos = courses.addModule(courseId, "Fundamentos");
        long oQueEUmaApi = courses.addPublishedLesson(fundamentos, "O que é uma API", "o-que-e-uma-api",
                "three-seconds.mp4");
        long httpNaPratica = courses.addLesson(fundamentos, "HTTP na prática", "http-na-pratica");
        courses.linkVideo(httpNaPratica, "three-seconds.mp4");
        long vazio = courses.addModule(courseId, "Módulo vazio");
        long rotas = courses.addModule(courseId, "Rotas e respostas");
        long rotasNoExpress = courses.addPublishedLesson(rotas, "Rotas no Express", "rotas-no-express",
                "five-seconds.mp4");
        return new Syllabus(fundamentos, oQueEUmaApi, httpNaPratica, vazio, rotas, rotasNoExpress);
    }

    private record Syllabus(long fundamentos, long oQueEUmaApi, long httpNaPratica, long vazio, long rotas,
                            long rotasNoExpress) {
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

    private static Map<String, List<String>> headersOf(MvcTestResult result) {
        return result.getResponse().getHeaderNames().stream()
                .collect(Collectors.toMap(name -> name, result.getResponse()::getHeaders));
    }

    /** The listed ids that are among the given ones, in the list's order. */
    private static List<Long> listedIdsAmong(MvcTestResult list, List<Long> ids) {
        return JsonPath.<List<Number>>read(body(list), "$.items[*].id").stream()
                .map(Number::longValue)
                .filter(ids::contains)
                .toList();
    }

    private static Map<String, Object> listedItem(MvcTestResult list, long id) {
        List<Map<String, Object>> matches = JsonPath.read(body(list), "$.items[?(@.id == %d)]".formatted(id));
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }
}
