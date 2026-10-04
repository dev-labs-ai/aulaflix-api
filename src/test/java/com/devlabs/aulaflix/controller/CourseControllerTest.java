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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredCourses;
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
    private JdbcTemplate jdbc;

    private BffApi bff;

    private AdminCourses courses;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD));
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
        StoredCourses stored = new StoredCourses(jdbc);
        long launchedStraightFromDraft = stored.insertOnSale(newSlug(), Instant.parse("2026-09-10T12:00:00Z"));
        long launchedAfterAnnouncement = stored.insertOnSale(newSlug(), Instant.parse("2026-08-01T12:00:00Z"),
                Instant.parse("2026-09-20T12:00:00Z"));
        long launchedFirst = stored.insertOnSale(newSlug(), Instant.parse("2026-09-01T12:00:00Z"));
        clock.set(Instant.parse("2026-10-04T12:00:00Z"));
        long comingSoon = courses.announced(newSlug());

        MvcTestResult list = bff.get("/v1/courses").exchange();

        assertThat(listedIdsAmong(list, List.of(comingSoon, launchedFirst, launchedStraightFromDraft,
                launchedAfterAnnouncement)))
                .containsExactly(launchedAfterAnnouncement, launchedStraightFromDraft, launchedFirst, comingSoon);
        assertThat(listedItem(list, launchedAfterAnnouncement)).containsEntry("status", "ON_SALE")
                .doesNotContainKey("plannedTopicCount");
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
    void showsAComingSoonCourseWithItsPlannedTopicsAndNoneOfItsModules() {
        String slug = newSlug();
        long id = courses.announced(slug);
        courses.addModule(id, "Fundamentos");

        MvcTestResult detail = bff.get("/v1/courses/" + slug).exchange();

        assertThat(detail).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson()
                .doesNotHavePath("$.pricing")
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
    void showsAnOnSaleCourseWithoutThePlannedTopicsItHadWhileComingSoon() {
        String slug = newSlug();
        long id = new StoredCourses(jdbc).insertOnSale(slug, Instant.parse("2026-08-01T12:00:00Z"),
                Instant.parse("2026-09-20T12:00:00Z"));

        assertThat(bff.get("/v1/courses/" + slug)).hasStatusOk().bodyJson()
                .doesNotHavePath("$.plannedTopics")
                .doesNotHavePath("$.plannedTopicCount")
                .isLenientlyEqualTo("""
                        {"id": %d, "slug": "%s", "status": "ON_SALE", "about": ["Por que testar."], "faq": []}"""
                        .formatted(id, slug));
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
