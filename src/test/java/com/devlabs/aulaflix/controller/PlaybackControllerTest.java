package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import com.devlabs.aulaflix.AistorContainer;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * Playback as the BFF asks for it: the Free lesson of an On sale Course for anyone, without a session, and no other
 * Lesson without one. The guards run in order: an Admin's session, then the Lesson, then the session. The presigned
 * GET itself runs over real HTTP in {@link PlaybackIT}.
 */
class PlaybackControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final DateTimeFormatter SIGNING_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    @Autowired
    private AccountService accounts;

    @Autowired
    private AistorContainer storage;

    @Autowired
    private StoredVideos storedVideos;

    private String adminToken;

    private AdminCourses courses;

    private BffApi bff;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        adminToken = new AdminApi(mvc).sessionToken(email, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        bff = new BffApi(mvc);
    }

    @Test
    void playsTheFreeLessonWithoutASessionThroughAUrlSignedWithTheReadOnlyKeyForFourHours() {
        long freeLesson = courses.freeLessonOf(courses.onSale(newSlug()));

        MvcTestResult result = playback(freeLesson);

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).hasHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        assertThat(result).bodyJson().extractingPath("$").asMap().containsOnlyKeys("url", "expiresAt");
        String url = JsonPath.read(body(result), "$.url");
        assertThat(url).startsWith(storage.endpoint() + pathOf(adminPlaybackUrlOf(freeLesson)) + "?");
        Map<String, String> query = queryOf(url);
        assertThat(query).containsEntry("X-Amz-Expires", "14400").containsEntry("X-Amz-SignedHeaders", "host");
        assertThat(query.get("X-Amz-Credential")).startsWith(key("read-only.access-key-id") + "/");
        assertThat(result).bodyJson().extractingPath("$.expiresAt")
                .isEqualTo(signedAt(query).plus(Duration.ofHours(4)).toString());
    }

    @Test
    void playsWhicheverLessonIsTheFreeLessonNowAndNoLongerTheOneBefore() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        long formerFreeLesson = courses.freeLessonOf(course);
        long rotasNoExpress = courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"),
                "Rotas no Express", "rotas-no-express", "five-seconds.mp4");

        courses.putDocument(course, AdminCourses.fullDocument(slug, rotasNoExpress));

        assertThat(playback(rotasNoExpress)).hasStatusOk();
        assertUnauthenticated(playback(formerFreeLesson), formerFreeLesson);
    }

    @Test
    void asksForASessionToPlayAnyOtherPublishedLesson() {
        long course = courses.onSale(newSlug());
        long lesson = courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"), "Rotas no Express",
                "rotas-no-express", "five-seconds.mp4");

        assertUnauthenticated(playback(lesson), lesson);
    }

    /** An "Em breve" Lesson does not exist for anyone but the Admin, even once its video is linked. */
    @Test
    void answersAnEmBreveLessonOfAnOnSaleCourseLikeAnUnknownOne() {
        long course = courses.onSale(newSlug());
        long emBreve = courses.addLesson(courses.addModule(course, "Rotas e respostas"), "Rotas no Express",
                "rotas-no-express");
        courses.linkVideo(emBreve, "five-seconds.mp4");

        assertLessonNotFound(playback(emBreve), emBreve);
    }

    @Test
    void answersTheFreeLessonOfADraftLikeAnUnknownLesson() {
        String slug = newSlug();
        long draft = courses.completeDraft(slug);
        long lesson = courses.addPublishedLesson(courses.addModule(draft, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        courses.putDocument(draft, AdminCourses.fullDocument(slug, lesson));

        assertLessonNotFound(playback(lesson), lesson);
    }

    @Test
    void answersTheFreeLessonOfAComingSoonCourseLikeAnUnknownLesson() {
        String slug = newSlug();
        long announced = courses.announced(slug);
        long lesson = courses.addPublishedLesson(courses.addModule(announced, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        courses.putDocument(announced, AdminCourses.fullDocument(slug, lesson));

        assertLessonNotFound(playback(lesson), lesson);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999999999", "0", "-1", "007", "1.5", "abc", "99999999999999999999"})
    void answersAnIdOfAnyShapeLikeAnUnknownLesson(String id) {
        String path = "/v1/lessons/%s/playback".formatted(id);

        assertProblem(bff.get(path).exchange(), path, HttpStatus.NOT_FOUND, "lesson-not-found", "Lesson not found",
                "No Lesson has this id.");
    }

    /** The Admin previews videos through their own endpoint, so their session is refused before anything else. */
    @Test
    void forbidsAnAdminEvenTheFreeLessonAndBeforeLookingTheLessonUp() {
        long course = courses.onSale(newSlug());
        long freeLesson = courses.freeLessonOf(course);
        long lesson = courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"), "Rotas no Express",
                "rotas-no-express", "five-seconds.mp4");

        assertForbidden(playback(freeLesson, adminToken), "/v1/lessons/%d/playback".formatted(freeLesson));
        assertForbidden(playback(lesson, adminToken), "/v1/lessons/%d/playback".formatted(lesson));
        assertForbidden(bff.get("/v1/lessons/abc/playback").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange(), "/v1/lessons/abc/playback");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bm8tc2Vzc2lvbi1oYXMtdGhpcy10b2tlbi0wMTIzNDU2Nzg5YWJj", "not a token!"})
    void refusesAnUnknownOrMalformedTokenEvenForTheFreeLesson(String token) {
        long freeLesson = courses.freeLessonOf(courses.onSale(newSlug()));

        assertUnauthenticated(playback(freeLesson, token), freeLesson);
    }

    @Test
    void refusesAnExpiredTokenEvenForTheFreeLesson() {
        long freeLesson = courses.freeLessonOf(courses.onSale(newSlug()));

        clock.set(clock.instant().plus(Duration.ofMinutes(31)));

        assertUnauthenticated(playback(freeLesson, adminToken), freeLesson);
    }

    @Test
    void refusesARevokedTokenEvenForTheFreeLesson() {
        long freeLesson = courses.freeLessonOf(courses.onSale(newSlug()));

        assertThat(new AdminApi(mvc).signOut(adminToken)).hasStatus(HttpStatus.NO_CONTENT);

        assertUnauthenticated(playback(freeLesson, adminToken), freeLesson);
    }

    private MvcTestResult playback(long lesson) {
        return bff.get("/v1/lessons/%d/playback".formatted(lesson)).exchange();
    }

    private MvcTestResult playback(long lesson, String token) {
        return bff.get("/v1/lessons/%d/playback".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private String adminPlaybackUrlOf(long lesson) {
        MvcTestResult preview = mvc.get().uri("/v1/admin/lessons/%d/playback".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
        assertThat(preview).hasStatusOk();
        return JsonPath.read(body(preview), "$.url");
    }

    private void assertUnauthenticated(MvcTestResult result, long lesson) {
        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertProblem(result, "/v1/lessons/%d/playback".formatted(lesson), HttpStatus.UNAUTHORIZED, "unauthenticated",
                "Unauthenticated", "This needs a valid session token, sent as Authorization: Bearer.");
    }

    private void assertForbidden(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "This session's role may not do this.");
    }

    private void assertLessonNotFound(MvcTestResult result, long lesson) {
        assertProblem(result, "/v1/lessons/%d/playback".formatted(lesson), HttpStatus.NOT_FOUND, "lesson-not-found",
                "Lesson not found", "No Lesson has this id.");
    }

    /** The whole ProblemDetail of a refusal that carries no extension. */
    private void assertProblem(MvcTestResult result, String path, HttpStatus status, String name, String title,
                               String detail) {
        assertThat(result).hasStatus(status)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/%s",
                          "title": "%s",
                          "status": %d,
                          "detail": "%s",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(name, title, status.value(), detail, path, clock.instant()));
    }

    private String key(String name) {
        return storage.applicationProperties().get("aulaflix.storage." + name).get().toString();
    }

    private static String pathOf(String url) {
        return URI.create(url).getRawPath();
    }

    private static Map<String, String> queryOf(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    /** The instant the URL was signed at, to the second, which its validity counts from. */
    private static Instant signedAt(Map<String, String> query) {
        return LocalDateTime.parse(query.get("X-Amz-Date"), SIGNING_DATE).toInstant(ZoneOffset.UTC);
    }
}
