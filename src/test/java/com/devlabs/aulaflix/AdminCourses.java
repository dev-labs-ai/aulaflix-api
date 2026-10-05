package com.devlabs.aulaflix;

import static com.devlabs.aulaflix.AdminApi.body;
import static org.assertj.core.api.Assertions.assertThat;

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

    public AdminCourses(MockMvcTester mvc, String adminToken) {
        this.mvc = mvc;
        this.bearer = "Bearer " + adminToken;
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
        assertThat(mvc.put().uri("/v1/admin/courses/" + id)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fullDocument(slug)))
                .hasStatusOk();
        return id;
    }

    /** A Course moved to Coming soon from {@link #fullDocument}, as of the application's clock. */
    public long announced(String slug) {
        long id = completeDraft(slug);
        assertThat(mvc.put().uri("/v1/admin/courses/" + id + "/status")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"COMING_SOON\"}"))
                .hasStatusOk();
        return id;
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

    private static long idOf(MvcTestResult created) {
        return ((Number) JsonPath.read(body(created), "$.id")).longValue();
    }

    public static String newSlug() {
        return "curso-" + UUID.randomUUID();
    }
}
