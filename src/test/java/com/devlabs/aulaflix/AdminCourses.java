package com.devlabs.aulaflix;

import static com.devlabs.aulaflix.AdminApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Puts Courses in place through the Admin endpoints, for the tests whose subject is something else. Each step fails
 * the test unless the endpoint takes it.
 */
public final class AdminCourses {

    private final MockMvcTester mvc;
    private final String bearer;
    private final StoredVideos videos;

    public AdminCourses(MockMvcTester mvc, String adminToken) {
        this(mvc, adminToken, null);
    }

    /** With the storage, where uploads go the way the Admin's curl sends them, so Lessons can be published. */
    public AdminCourses(MockMvcTester mvc, String adminToken, StoredVideos videos) {
        this.mvc = mvc;
        this.bearer = "Bearer " + adminToken;
        this.videos = videos;
    }

    /** A Draft with nothing but its slug and title. */
    public long draft(String slug) {
        MvcTestResult created = mvc.post().uri("/v1/admin/courses")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"slug\": \"%s\", \"title\": \"Backend com Node.js\"}".formatted(slug))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return idOf(created);
    }

    /** A Draft whose document is {@link #fullDocument}. */
    public long completeDraft(String slug) {
        long id = draft(slug);
        putDocument(id, fullDocument(slug));
        return id;
    }

    /** A Course moved to Coming soon from {@link #fullDocument}, as of the application's clock. */
    public long announced(String slug) {
        long id = completeDraft(slug);
        moveTo(id, "COMING_SOON");
        return id;
    }

    /**
     * A Course moved to On sale straight from a Draft of {@link #fullDocument}, as of the application's clock. Its one
     * Module, "Fundamentos", holds its Free lesson, "O que é uma API", published with a three-second video.
     */
    public long onSale(String slug) {
        return launch(completeDraft(slug), slug);
    }

    /** Like {@link #onSale}, from a Course announced first, so it went Coming soon before it went On sale. */
    public long launchedAfterAnnouncement(String slug) {
        return launch(announced(slug), slug);
    }

    /**
     * Moves a Course of {@link #fullDocument}, Draft or Coming soon, to On sale, as of the application's clock: a new
     * Module, "Fundamentos", gets its Free lesson, "O que é uma API", published with a three-second video.
     */
    public long launch(long courseId, String slug) {
        long module = addModule(courseId, "Fundamentos");
        long freeLesson = addPublishedLesson(module, "O que é uma API", "o-que-e-uma-api", "three-seconds.mp4");
        putDocument(courseId, fullDocument(slug, freeLesson));
        moveTo(courseId, "ON_SALE");
        return courseId;
    }

    /** The id of the Course's Free lesson, as its document names it. */
    public long freeLessonOf(long courseId) {
        MvcTestResult course = mvc.get().uri("/v1/admin/courses/" + courseId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange();
        assertThat(course).hasStatusOk();
        return ((Number) JsonPath.read(body(course), "$.freeLessonId")).longValue();
    }

    /** The new Module's id. */
    public long addModule(long courseId, String title) {
        MvcTestResult created = mvc.post().uri("/v1/admin/courses/" + courseId + "/modules")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"%s\"}".formatted(title))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return idOf(created);
    }

    /** The new Lesson's id. */
    public long addLesson(long moduleId, String title, String slug) {
        MvcTestResult created = mvc.post().uri("/v1/admin/modules/" + moduleId + "/lessons")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\": \"%s\", \"slug\": \"%s\"}".formatted(title, slug))
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return idOf(created);
    }

    /** A Lesson in a Module of a new Draft. */
    public long lessonOfANewDraft() {
        return addLesson(addModule(draft(newSlug()), "Fundamentos"), "O que é uma API", "o-que-e-uma-api");
    }

    /** A new Lesson at the end of the Module, published with a video of the fixture's length. */
    public long addPublishedLesson(long moduleId, String title, String slug, String fixture) {
        long lesson = addLesson(moduleId, title, slug);
        linkVideo(lesson, fixture);
        publish(lesson);
        return lesson;
    }

    /**
     * Links one of {@link StoredVideos#fixture}'s files the way the Admin does: an upload URL, the upload straight to
     * the storage, then the link.
     */
    public void linkVideo(long lessonId, String fixture) {
        Objects.requireNonNull(videos, "Linking a video needs the storage");
        MvcTestResult upload = mvc.post().uri("/v1/admin/lessons/%d/video-uploads".formatted(lessonId))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange();
        assertThat(upload).hasStatusOk();
        String objectKey = JsonPath.read(body(upload), "$.objectKey");
        videos.put(objectKey, StoredVideos.fixture(fixture));
        put("/v1/admin/lessons/%d/video".formatted(lessonId), "{\"objectKey\": \"%s\"}".formatted(objectKey));
    }

    public void publish(long lessonId) {
        put("/v1/admin/lessons/%d/status".formatted(lessonId), "{\"status\": \"PUBLISHED\"}");
    }

    public void putDocument(long courseId, String document) {
        put("/v1/admin/courses/" + courseId, document);
    }

    /** The outline, {@code [{ moduleId, lessonIds }, …]}, as JSON. */
    public String outline(long courseId) {
        MvcTestResult outline = mvc.get().uri("/v1/admin/courses/%d/outline".formatted(courseId))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange();
        assertThat(outline).hasStatusOk();
        return body(outline);
    }

    /** Puts the outline, {@code [{ moduleId, lessonIds }, …]}, in the order given. */
    public void putOutline(long courseId, String outline) {
        put("/v1/admin/courses/%d/outline".formatted(courseId), outline);
    }

    public void moveTo(long courseId, String status) {
        put("/v1/admin/courses/%d/status".formatted(courseId), "{\"status\": \"%s\"}".formatted(status));
    }

    public void delete(long courseId) {
        assertThat(mvc.delete().uri("/v1/admin/courses/" + courseId).header(HttpHeaders.AUTHORIZATION, bearer))
                .hasStatus(HttpStatus.NO_CONTENT);
    }

    /** Every field set, ready to go Coming soon: two paragraphs about the Course, two Planned topics, a price. */
    public static String fullDocument(String slug) {
        return """
                {
                  "slug": "%s",
                  "title": "Backend com Node.js",
                  "summary": "Construa APIs REST com Node.js e TypeScript.",
                  "area": "BACKEND",
                  "icon": "SERVER",
                  "tone": "CORAL",
                  "about": ["Quase todo produto depende de um backend.", "Este curso constrói uma API do zero."],
                  "learn": ["Projetar rotas e respostas."],
                  "audience": ["Para devs frontend."],
                  "plannedTopics": ["Fundamentos de APIs.", "Autenticação."],
                  "faq": [{"question": "Preciso saber JavaScript?", "answer": "Sim, o básico."}],
                  "priceCents": 49700,
                  "pixDiscountPercent": 10,
                  "maxInstallments": 10
                }""".formatted(slug);
    }

    /** {@link #fullDocument} with the Free lesson set, ready to go On sale too. */
    public static String fullDocument(String slug, long freeLessonId) {
        return JsonPath.parse(fullDocument(slug)).put("$", "freeLessonId", freeLessonId).jsonString();
    }

    private void put(String path, String body) {
        assertThat(mvc.put().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .hasStatusOk();
    }

    private static long idOf(MvcTestResult created) {
        return ((Number) JsonPath.read(body(created), "$.id")).longValue();
    }

    public static String newSlug() {
        return "curso-" + UUID.randomUUID();
    }
}
