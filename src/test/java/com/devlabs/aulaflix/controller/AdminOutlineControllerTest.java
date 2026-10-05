package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

class AdminOutlineControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

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
    void appendsEachNewModuleAtTheEndOfTheOutline() {
        long courseId = createCourse();

        MvcTestResult first = addModule(courseId, "Fundamentos");
        long second = idOf(addModule(courseId, "Rotas e respostas"));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        long firstId = idOf(first);
        assertThat(first).hasHeader(HttpHeaders.LOCATION, "/v1/admin/modules/" + firstId)
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {"id": %d, "courseId": %d, "title": "Fundamentos"}""".formatted(firstId, courseId));
        assertThat(outline(courseId)).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        [
                          {"moduleId": %d, "lessonIds": []},
                          {"moduleId": %d, "lessonIds": []}
                        ]""".formatted(firstId, second));
    }

    @Test
    void appendsEachNewLessonAtTheEndOfItsModule() {
        long courseId = createCourse();
        long fundamentos = idOf(addModule(courseId, "Fundamentos"));
        long rotas = idOf(addModule(courseId, "Rotas e respostas"));

        MvcTestResult first = addLesson(fundamentos, "O que é uma API", "o-que-e-uma-api");
        long second = idOf(addLesson(rotas, "Rotas no Express", "rotas-no-express"));
        long third = idOf(addLesson(fundamentos, "HTTP na prática", "http-na-pratica"));

        assertThat(first).hasStatus(HttpStatus.CREATED);
        long firstId = idOf(first);
        assertThat(first).hasHeader(HttpHeaders.LOCATION, "/v1/admin/lessons/" + firstId)
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "id": %d,
                          "moduleId": %d,
                          "title": "O que é uma API",
                          "slug": "o-que-e-uma-api",
                          "published": false
                        }""".formatted(firstId, fundamentos));
        assertThat(outline(courseId)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                [
                  {"moduleId": %d, "lessonIds": [%d, %d]},
                  {"moduleId": %d, "lessonIds": [%d]}
                ]""".formatted(fundamentos, firstId, third, rotas, second));
    }

    @Test
    void refusesASlugAnotherLessonOfTheCourseHasAndAddsNothing() {
        long courseId = createCourse();
        long fundamentos = idOf(addModule(courseId, "Fundamentos"));
        long rotas = idOf(addModule(courseId, "Rotas e respostas"));
        long lesson = idOf(addLesson(fundamentos, "O que é uma API", "o-que-e-uma-api"));

        assertLessonSlugTaken(addLesson(rotas, "APIs, de novo", "o-que-e-uma-api"),
                "/v1/admin/modules/%d/lessons".formatted(rotas));
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo("""
                [
                  {"moduleId": %d, "lessonIds": [%d]},
                  {"moduleId": %d, "lessonIds": []}
                ]""".formatted(fundamentos, lesson, rotas));
    }

    @Test
    void acceptsASlugALessonOfAnotherCourseHas() {
        long otherModule = idOf(addModule(createCourse(), "Fundamentos"));
        assertThat(addLesson(otherModule, "O que é uma API", "o-que-e-uma-api")).hasStatus(HttpStatus.CREATED);
        long module = idOf(addModule(createCourse(), "Fundamentos"));

        assertThat(addLesson(module, "O que é uma API", "o-que-e-uma-api")).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.slug").isEqualTo("o-que-e-uma-api");
    }

    @Test
    void renamesAModuleInPlace() {
        long courseId = createCourse();
        long fundamentos = idOf(addModule(courseId, "Fundamentos"));
        long rotas = idOf(addModule(courseId, "Rotas e respostas"));

        assertThat(renameModule(fundamentos, "Fundamentos de APIs")).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {"id": %d, "courseId": %d, "title": "Fundamentos de APIs"}""".formatted(fundamentos, courseId));
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo("""
                [
                  {"moduleId": %d, "lessonIds": []},
                  {"moduleId": %d, "lessonIds": []}
                ]""".formatted(fundamentos, rotas));
    }

    @Test
    void deletesAnEmptyModuleWhichIsThenNotFound() {
        long courseId = createCourse();
        long fundamentos = idOf(addModule(courseId, "Fundamentos"));
        long rotas = idOf(addModule(courseId, "Rotas e respostas"));

        assertThat(deleteModule(fundamentos)).hasStatus(HttpStatus.NO_CONTENT).body().isEmpty();

        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo("""
                [{"moduleId": %d, "lessonIds": []}]""".formatted(rotas));
        String path = "/v1/admin/modules/" + fundamentos;
        assertModuleNotFound(renameModule(fundamentos, "Fundamentos de APIs"), path);
        assertModuleNotFound(deleteModule(fundamentos), path);
        assertModuleNotFound(addLesson(fundamentos, "O que é uma API", "o-que-e-uma-api"), path + "/lessons");
    }

    @Test
    void refusesToDeleteAModuleThatHasLessonsAndChangesNothing() {
        long courseId = createCourse();
        long fundamentos = idOf(addModule(courseId, "Fundamentos"));
        long lesson = idOf(addLesson(fundamentos, "O que é uma API", "o-que-e-uma-api"));

        assertProblem(deleteModule(fundamentos), "/v1/admin/modules/" + fundamentos, HttpStatus.CONFLICT,
                "module-not-empty", "Module not empty", "Only a Module without Lessons can be deleted.");
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo("""
                [{"moduleId": %d, "lessonIds": [%d]}]""".formatted(fundamentos, lesson));
    }

    @Test
    void editsAnUnpublishedLessonsTitleAndSlug() {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));
        long lesson = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));

        assertThat(editLesson(lesson, "O que é uma API REST", "o-que-e-uma-api-rest")).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "id": %d,
                          "moduleId": %d,
                          "title": "O que é uma API REST",
                          "slug": "o-que-e-uma-api-rest",
                          "published": false
                        }""".formatted(lesson, module));
        assertLessonSlugTaken(addLesson(module, "Outra aula", "o-que-e-uma-api-rest"),
                "/v1/admin/modules/%d/lessons".formatted(module));
        assertThat(addLesson(module, "O que é uma API", "o-que-e-uma-api")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void keepsALessonsSlugWhenOnlyItsTitleChanges() {
        long module = idOf(addModule(createCourse(), "Fundamentos"));
        long lesson = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));

        assertThat(editLesson(lesson, "O que é uma API REST", "o-que-e-uma-api")).hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "id": %d,
                          "moduleId": %d,
                          "title": "O que é uma API REST",
                          "slug": "o-que-e-uma-api",
                          "published": false
                        }""".formatted(lesson, module));
    }

    @Test
    void refusesToMoveALessonOntoTheSlugOfAnotherLessonOfTheCourseAndChangesNothing() {
        long courseId = createCourse();
        long fundamentos = idOf(addModule(courseId, "Fundamentos"));
        long rotas = idOf(addModule(courseId, "Rotas e respostas"));
        assertThat(addLesson(fundamentos, "O que é uma API", "o-que-e-uma-api")).hasStatus(HttpStatus.CREATED);
        long lesson = idOf(addLesson(rotas, "Rotas no Express", "rotas-no-express"));

        assertLessonSlugTaken(editLesson(lesson, "Rotas no Fastify", "o-que-e-uma-api"),
                "/v1/admin/lessons/" + lesson);
        assertLessonSlugTaken(addLesson(fundamentos, "Outra aula", "rotas-no-express"),
                "/v1/admin/modules/%d/lessons".formatted(fundamentos));
    }

    @Test
    void deletesAnUnpublishedLessonWhichIsThenNotFound() {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));
        long first = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));
        long second = idOf(addLesson(module, "HTTP na prática", "http-na-pratica"));

        assertThat(deleteLesson(first)).hasStatus(HttpStatus.NO_CONTENT).body().isEmpty();

        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo("""
                [{"moduleId": %d, "lessonIds": [%d]}]""".formatted(module, second));
        String path = "/v1/admin/lessons/" + first;
        assertLessonNotFound(editLesson(first, "O que é uma API", "o-que-e-uma-api"), path);
        assertLessonNotFound(deleteLesson(first), path);
        assertThat(addLesson(module, "O que é uma API", "o-que-e-uma-api")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void publishesALessonWithALinkedVideoAndAnswersARepeatWithoutChangingIt() {
        long module = idOf(addModule(createCourse(), "Fundamentos"));
        long lesson = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));
        courses().linkVideo(lesson, "three-seconds.mp4");
        clock.set(Instant.parse("2026-10-04T10:00:00.123456789Z"));

        MvcTestResult published = publish(lesson);

        String expected = """
                {
                  "id": %d,
                  "moduleId": %d,
                  "title": "O que é uma API",
                  "slug": "o-que-e-uma-api",
                  "durationSeconds": 3,
                  "published": true,
                  "publishedAt": "2026-10-04T10:00:00.123456Z"
                }""".formatted(lesson, module);
        assertThat(published).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo(expected);
        assertThat(editLesson(lesson, "O que é uma API", "o-que-e-uma-api")).bodyJson().isStrictlyEqualTo(expected);

        clock.set(Instant.parse("2026-10-04T11:00:00Z"));

        assertThat(publish(lesson)).hasStatusOk().bodyJson().isStrictlyEqualTo(expected);
        assertThat(editLesson(lesson, "O que é uma API", "o-que-e-uma-api")).bodyJson().isStrictlyEqualTo(expected);
    }

    @Test
    void refusesToPublishALessonWithoutAVideoAndChangesNothing() {
        long module = idOf(addModule(createCourse(), "Fundamentos"));
        long lesson = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));
        String path = "/v1/admin/lessons/%d/status".formatted(lesson);

        assertProblem(publish(lesson), path, HttpStatus.CONFLICT, "video-required", "Video required",
                "A Lesson is published only with a video: upload one and link it first.");
        assertThat(editLesson(lesson, "O que é uma API", "o-que-e-uma-api")).bodyJson()
                .extractingPath("$.published").isEqualTo(false);
        assertThat(deleteLesson(lesson)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "no status            | {}                          | required",
            "null status          | {\"status\": null}          | required",
            "unpublished          | {\"status\": \"UNPUBLISHED\"} | invalid-format",
            "status in lower case | {\"status\": \"published\"}   | invalid-format"})
    void refusesAPublicationWithoutTheOnlyStatusALessonMovesTo(String description, String request, String code) {
        long lesson = idOf(addLesson(idOf(addModule(createCourse(), "Fundamentos")), "O que é uma API",
                "o-que-e-uma-api"));
        courses().linkVideo(lesson, "three-seconds.mp4");

        assertInvalidRequest(put("/v1/admin/lessons/%d/status".formatted(lesson), request),
                "/v1/admin/lessons/%d/status".formatted(lesson), "status", code);
        assertThat(editLesson(lesson, "O que é uma API", "o-que-e-uma-api")).bodyJson()
                .extractingPath("$.published").isEqualTo(false);
    }

    @Test
    void neverUnpublishesALesson() {
        long module = idOf(addModule(createCourse(), "Fundamentos"));
        long lesson = courses().addPublishedLesson(module, "O que é uma API", "o-que-e-uma-api", "three-seconds.mp4");

        assertThat(put("/v1/admin/lessons/%d/status".formatted(lesson), "{\"status\": \"UNPUBLISHED\"}"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(editLesson(lesson, "O que é uma API", "o-que-e-uma-api")).bodyJson()
                .extractingPath("$.published").isEqualTo(true);
    }

    @Test
    void refusesToDeleteAPublishedLessonAndChangesNothing() {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));
        long lesson = courses().addPublishedLesson(module, "O que é uma API", "o-que-e-uma-api", "three-seconds.mp4");
        String before = body(editLesson(lesson, "O que é uma API", "o-que-e-uma-api"));

        assertProblem(deleteLesson(lesson), "/v1/admin/lessons/" + lesson, HttpStatus.CONFLICT, "lesson-published",
                "Lesson published", "A published Lesson is never deleted.");
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo("""
                [{"moduleId": %d, "lessonIds": [%d]}]""".formatted(module, lesson));
        assertThat(editLesson(lesson, "O que é uma API", "o-que-e-uma-api")).bodyJson().isStrictlyEqualTo(before);
        assertThat(storedVideos.keysOf(lesson)).hasSize(1);
    }

    @Test
    void refusesToChangeAPublishedLessonsSlugAndChangesNothingButTakesANewTitle() {
        long module = idOf(addModule(createCourse(), "Fundamentos"));
        long lesson = courses().addPublishedLesson(module, "O que é uma API", "o-que-e-uma-api", "three-seconds.mp4");

        assertProblem(editLesson(lesson, "O que é uma API REST", "o-que-e-uma-api-rest"),
                "/v1/admin/lessons/" + lesson, HttpStatus.CONFLICT, "lesson-slug-frozen", "Lesson slug frozen",
                "The slug of a published Lesson never changes.");
        assertThat(addLesson(module, "Outra aula", "o-que-e-uma-api-rest")).hasStatus(HttpStatus.CREATED);
        assertThat(editLesson(lesson, "O que é uma API REST", "o-que-e-uma-api")).hasStatusOk()
                .bodyJson().isLenientlyEqualTo("""
                        {"title": "O que é uma API REST", "slug": "o-que-e-uma-api", "published": true}""");
    }

    @Test
    void reordersModulesAndMovesLessonsBetweenThemInOneCall() {
        long courseId = createCourse();
        long fundamentos = idOf(addModule(courseId, "Fundamentos"));
        long rotas = idOf(addModule(courseId, "Rotas e respostas"));
        long testes = idOf(addModule(courseId, "Testes"));
        long api = idOf(addLesson(fundamentos, "O que é uma API", "o-que-e-uma-api"));
        long http = idOf(addLesson(fundamentos, "HTTP na prática", "http-na-pratica"));
        long express = idOf(addLesson(rotas, "Rotas no Express", "rotas-no-express"));
        String reordered = """
                [
                  {"moduleId": %d, "lessonIds": [%d]},
                  {"moduleId": %d, "lessonIds": [%d, %d]},
                  {"moduleId": %d, "lessonIds": []}
                ]""".formatted(testes, http, fundamentos, express, api, rotas);

        assertThat(putOutline(courseId, reordered)).hasStatusOk()
                .hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo(reordered);
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo(reordered);
    }

    @Test
    void appendsAfterTheModulesAndLessonsAsTheyWereReordered() {
        long courseId = createCourse();
        long fundamentos = idOf(addModule(courseId, "Fundamentos"));
        long rotas = idOf(addModule(courseId, "Rotas e respostas"));
        long api = idOf(addLesson(fundamentos, "O que é uma API", "o-que-e-uma-api"));
        long http = idOf(addLesson(fundamentos, "HTTP na prática", "http-na-pratica"));
        assertThat(putOutline(courseId, """
                [{"moduleId": %d, "lessonIds": [%d, %d]}, {"moduleId": %d, "lessonIds": []}]"""
                .formatted(rotas, http, api, fundamentos))).hasStatusOk();

        long testes = idOf(addModule(courseId, "Testes"));
        long express = idOf(addLesson(rotas, "Rotas no Express", "rotas-no-express"));

        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo("""
                [
                  {"moduleId": %d, "lessonIds": [%d, %d, %d]},
                  {"moduleId": %d, "lessonIds": []},
                  {"moduleId": %d, "lessonIds": []}
                ]""".formatted(rotas, http, api, express, fundamentos, testes));
    }

    /**
     * The Course has Modules {@code A} (Lessons {@code a1}, {@code a2}) and {@code B} ({@code b1}); another Course has
     * Module {@code X} ({@code x1}). Each outline names them by those letters.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("outlinesOtherThanTheCurrentOne")
    void refusesAnOutlineThatIsNotExactlyTheCurrentOneAndChangesNothing(String description, String outline) {
        long courseId = createCourse();
        long a = idOf(addModule(courseId, "Fundamentos"));
        long b = idOf(addModule(courseId, "Rotas e respostas"));
        long a1 = idOf(addLesson(a, "O que é uma API", "o-que-e-uma-api"));
        long a2 = idOf(addLesson(a, "HTTP na prática", "http-na-pratica"));
        long b1 = idOf(addLesson(b, "Rotas no Express", "rotas-no-express"));
        long otherCourseId = createCourse();
        long x = idOf(addModule(otherCourseId, "Fundamentos"));
        long x1 = idOf(addLesson(x, "O que é uma API", "o-que-e-uma-api"));
        String before = body(outline(courseId));
        String otherBefore = body(outline(otherCourseId));
        Map<String, Long> ids = Map.of("A", a, "B", b, "X", x, "a1", a1, "a2", a2, "b1", b1, "x1", x1);

        MvcTestResult result = putOutline(courseId, Pattern.compile("\\b(A|B|X|a1|a2|b1|x1)\\b").matcher(outline)
                .replaceAll(name -> Long.toString(ids.get(name.group()))));

        assertProblem(result, "/v1/admin/courses/%d/outline".formatted(courseId), HttpStatus.CONFLICT,
                "outline-mismatch", "Outline mismatch",
                "The outline must name every current Module and Lesson of the Course, each once.");
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo(before);
        assertThat(outline(otherCourseId)).bodyJson().isStrictlyEqualTo(otherBefore);
    }

    static Stream<Arguments> outlinesOtherThanTheCurrentOne() {
        String unknown = "999999999999999999";
        return Stream.of(
                Arguments.of("no Module", "[]"),
                Arguments.of("omits a Module", """
                        [{"moduleId": A, "lessonIds": [a1, a2, b1]}]"""),
                Arguments.of("omits a Lesson", """
                        [{"moduleId": B, "lessonIds": [b1]}, {"moduleId": A, "lessonIds": [a2]}]"""),
                Arguments.of("adds an unknown Module", """
                        [{"moduleId": A, "lessonIds": [a1, a2]}, {"moduleId": B, "lessonIds": [b1]},
                         {"moduleId": %s, "lessonIds": []}]""".formatted(unknown)),
                Arguments.of("adds an unknown Lesson", """
                        [{"moduleId": A, "lessonIds": [a1, a2, %s]}, {"moduleId": B, "lessonIds": [b1]}]"""
                        .formatted(unknown)),
                Arguments.of("names a Module twice", """
                        [{"moduleId": A, "lessonIds": [a1, a2]}, {"moduleId": B, "lessonIds": [b1]},
                         {"moduleId": A, "lessonIds": []}]"""),
                Arguments.of("names a Module twice instead of another", """
                        [{"moduleId": A, "lessonIds": [a1, a2]}, {"moduleId": A, "lessonIds": [b1]}]"""),
                Arguments.of("names a Lesson twice in one Module", """
                        [{"moduleId": A, "lessonIds": [a1, a2, a1]}, {"moduleId": B, "lessonIds": [b1]}]"""),
                Arguments.of("names a Lesson in two Modules", """
                        [{"moduleId": A, "lessonIds": [a1, a2]}, {"moduleId": B, "lessonIds": [b1, a2]}]"""),
                Arguments.of("names a Lesson twice instead of another", """
                        [{"moduleId": A, "lessonIds": [a1, a1]}, {"moduleId": B, "lessonIds": [b1]}]"""),
                Arguments.of("borrows another Course's Module", """
                        [{"moduleId": A, "lessonIds": [a1, a2]}, {"moduleId": B, "lessonIds": [b1]},
                         {"moduleId": X, "lessonIds": []}]"""),
                Arguments.of("borrows another Course's Lesson", """
                        [{"moduleId": A, "lessonIds": [a1, a2]}, {"moduleId": B, "lessonIds": [b1, x1]}]"""),
                Arguments.of("swaps a Module for another Course's", """
                        [{"moduleId": A, "lessonIds": [a1, a2, b1]}, {"moduleId": X, "lessonIds": []}]"""),
                Arguments.of("swaps a Lesson for another Course's", """
                        [{"moduleId": A, "lessonIds": [a1, a2]}, {"moduleId": B, "lessonIds": [x1]}]"""));
    }

    /** The Course has Modules {@code A} (Lesson {@code a1}) and {@code B}; each outline names them by those letters. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOutlines")
    void refusesAnOutlineWithAMissingOrUnreadableIdWithItsCode(String description, String outline, String field,
                                                              String code) {
        long courseId = createCourse();
        long a = idOf(addModule(courseId, "Fundamentos"));
        long b = idOf(addModule(courseId, "Rotas e respostas"));
        long a1 = idOf(addLesson(a, "O que é uma API", "o-que-e-uma-api"));
        String before = body(outline(courseId));
        Map<String, Long> ids = Map.of("A", a, "B", b, "a1", a1);

        MvcTestResult result = putOutline(courseId, Pattern.compile("\\b(A|B|a1)\\b").matcher(outline)
                .replaceAll(name -> Long.toString(ids.get(name.group()))));

        assertInvalidRequest(result, "/v1/admin/courses/%d/outline".formatted(courseId), field, code);
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo(before);
    }

    static Stream<Arguments> invalidOutlines() {
        return Stream.of(
                Arguments.of("null Module", """
                        [{"moduleId": A, "lessonIds": [a1]}, null]""", "[1]", "required"),
                Arguments.of("no Module id", """
                        [{"moduleId": A, "lessonIds": [a1]}, {"lessonIds": []}]""", "[1].moduleId", "required"),
                Arguments.of("null Module id", """
                        [{"moduleId": null, "lessonIds": [a1]}, {"moduleId": B, "lessonIds": []}]""",
                        "[0].moduleId", "required"),
                Arguments.of("no Lesson ids", """
                        [{"moduleId": A, "lessonIds": [a1]}, {"moduleId": B}]""", "[1].lessonIds", "required"),
                Arguments.of("null Lesson id", """
                        [{"moduleId": A, "lessonIds": [a1, null]}, {"moduleId": B, "lessonIds": []}]""",
                        "[0].lessonIds[1]", "required"),
                Arguments.of("Module id that is no number", """
                        [{"moduleId": A, "lessonIds": [a1]}, {"moduleId": "B-2", "lessonIds": []}]""",
                        "[1].moduleId", "invalid-format"),
                Arguments.of("fractional Lesson id", """
                        [{"moduleId": A, "lessonIds": [1.5]}, {"moduleId": B, "lessonIds": [a1]}]""",
                        "[0].lessonIds[0]", "invalid-format"),
                Arguments.of("Lesson id beyond any id", """
                        [{"moduleId": A, "lessonIds": [a1]}, {"moduleId": B, "lessonIds": [99999999999999999999]}]""",
                        "[1].lessonIds[0]", "out-of-range"));
    }

    @Test
    void refusesAnOutlineThatIsNotAList() {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));

        assertThat(putOutline(courseId, """
                {"moduleId": %d, "lessonIds": []}""".formatted(module))).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "The body is not the JSON this endpoint expects.",
                          "instance": "/v1/admin/courses/%d/outline",
                          "timestamp": "%s"
                        }""".formatted(courseId, clock.instant()));
    }

    @Test
    void deletingADraftCourseDeletesItsModulesAndLessons() {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));
        long lesson = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));
        assertThat(addModule(courseId, "Rotas e respostas")).hasStatus(HttpStatus.CREATED);

        assertThat(delete("/v1/admin/courses/" + courseId)).hasStatus(HttpStatus.NO_CONTENT);

        String coursePath = "/v1/admin/courses/" + courseId;
        assertCourseNotFound(outline(courseId), coursePath + "/outline");
        assertCourseNotFound(addModule(courseId, "Testes"), coursePath + "/modules");
        String modulePath = "/v1/admin/modules/" + module;
        assertModuleNotFound(renameModule(module, "Fundamentos de APIs"), modulePath);
        assertModuleNotFound(deleteModule(module), modulePath);
        assertModuleNotFound(addLesson(module, "HTTP na prática", "http-na-pratica"), modulePath + "/lessons");
        String lessonPath = "/v1/admin/lessons/" + lesson;
        assertLessonNotFound(editLesson(lesson, "O que é uma API", "o-que-e-uma-api"), lessonPath);
        assertLessonNotFound(deleteLesson(lesson), lessonPath);
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999999999", "0", "-1", "007", "1.5", "abc", "99999999999999999999"})
    void answersAnIdOfAnyShapeLikeAnUnknownOne(String id) {
        String course = "/v1/admin/courses/" + id;
        String module = "/v1/admin/modules/" + id;
        String lesson = "/v1/admin/lessons/" + id;
        String moduleRequest = "{\"title\": \"Fundamentos\"}";
        String lessonRequest = "{\"title\": \"O que é uma API\", \"slug\": \"o-que-e-uma-api\"}";

        assertCourseNotFound(get(course + "/outline"), course + "/outline");
        assertCourseNotFound(put(course + "/outline", "[]"), course + "/outline");
        assertCourseNotFound(post(course + "/modules", moduleRequest), course + "/modules");
        assertModuleNotFound(put(module, moduleRequest), module);
        assertModuleNotFound(delete(module), module);
        assertModuleNotFound(post(module + "/lessons", lessonRequest), module + "/lessons");
        assertLessonNotFound(put(lesson, lessonRequest), lesson);
        assertLessonNotFound(delete(lesson), lesson);
        assertLessonNotFound(put(lesson + "/status", "{\"status\": \"PUBLISHED\"}"), lesson + "/status");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void refusesEveryEndpointWithoutASessionAndChangesNothing(HttpMethod method, String path, String request) {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));
        long lesson = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));
        String before = body(outline(courseId));

        URI uri = expand(path, courseId, module, lesson);
        MvcTestResult result = mvc.perform(MockMvcRequestBuilders.request(method, uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.formatted(module, lesson)));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/unauthenticated");
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo(before);
        assertThat(editLesson(lesson, "O que é uma API", "o-que-e-uma-api")).hasStatusOk();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void refusesEveryEndpointOnceTheAdminSignedOut(HttpMethod method, String path, String request) {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));
        long lesson = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));
        String signedOut = token;
        new AdminApi(mvc).signOut(signedOut);

        URI uri = expand(path, courseId, module, lesson);
        MvcTestResult result = mvc.perform(MockMvcRequestBuilders.request(method, uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signedOut)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.formatted(module, lesson)));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/unauthenticated");
    }

    /** Each path takes the ids of a Course, of a Module and of a Lesson it has, and each body is valid for them. */
    static Stream<Arguments> everyEndpoint() {
        String moduleRequest = "{\"title\": \"Testes\"}";
        String lessonRequest = "{\"title\": \"HTTP na prática\", \"slug\": \"http-na-pratica\"}";
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/v1/admin/courses/{courseId}/outline", ""),
                Arguments.of(HttpMethod.PUT, "/v1/admin/courses/{courseId}/outline",
                        "[{\"moduleId\": %d, \"lessonIds\": [%d]}]"),
                Arguments.of(HttpMethod.POST, "/v1/admin/courses/{courseId}/modules", moduleRequest),
                Arguments.of(HttpMethod.PUT, "/v1/admin/modules/{moduleId}", moduleRequest),
                Arguments.of(HttpMethod.DELETE, "/v1/admin/modules/{moduleId}", ""),
                Arguments.of(HttpMethod.POST, "/v1/admin/modules/{moduleId}/lessons", lessonRequest),
                Arguments.of(HttpMethod.PUT, "/v1/admin/lessons/{lessonId}", lessonRequest),
                Arguments.of(HttpMethod.DELETE, "/v1/admin/lessons/{lessonId}", ""),
                Arguments.of(HttpMethod.PUT, "/v1/admin/lessons/{lessonId}/status", "{\"status\": \"PUBLISHED\"}"));
    }

    private static URI expand(String path, long courseId, long moduleId, long lessonId) {
        return UriComponentsBuilder.fromPath(path)
                .buildAndExpand(Map.of("courseId", courseId, "moduleId", moduleId, "lessonId", lessonId))
                .toUri();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidModules")
    void refusesAnInvalidModuleTitleWithOneCode(String description, String request, String code) {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));
        String before = body(outline(courseId));

        assertInvalidRequest(post("/v1/admin/courses/%d/modules".formatted(courseId), request),
                "/v1/admin/courses/%d/modules".formatted(courseId), "title", code);
        assertInvalidRequest(put("/v1/admin/modules/" + module, request), "/v1/admin/modules/" + module, "title",
                code);
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo(before);
    }

    static Stream<Arguments> invalidModules() {
        return Stream.of(
                Arguments.of("no title", "{}", "required"),
                Arguments.of("null title", "{\"title\": null}", "required"),
                Arguments.of("blank title", "{\"title\": \"  \"}", "required"),
                Arguments.of("title of 121 characters", "{\"title\": \"%s\"}".formatted("a".repeat(121)),
                        "too-long"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidLessons")
    void refusesAnInvalidLessonTitleOrSlugWithOneCode(String description, String request, String field,
                                                       String code) {
        long courseId = createCourse();
        long module = idOf(addModule(courseId, "Fundamentos"));
        long lesson = idOf(addLesson(module, "O que é uma API", "o-que-e-uma-api"));
        String before = body(outline(courseId));

        assertInvalidRequest(post("/v1/admin/modules/%d/lessons".formatted(module), request),
                "/v1/admin/modules/%d/lessons".formatted(module), field, code);
        assertInvalidRequest(put("/v1/admin/lessons/" + lesson, request), "/v1/admin/lessons/" + lesson, field,
                code);
        assertThat(outline(courseId)).bodyJson().isStrictlyEqualTo(before);
        assertThat(editLesson(lesson, "O que é uma API", "o-que-e-uma-api")).bodyJson()
                .extractingPath("$.title").isEqualTo("O que é uma API");
    }

    static Stream<Arguments> invalidLessons() {
        String title = "\"title\": \"Estado com hooks\"";
        String slug = "\"slug\": \"estado-com-hooks\"";
        return Stream.of(
                Arguments.of("no title", "{%s}".formatted(slug), "title", "required"),
                Arguments.of("blank title", "{\"title\": \" \", %s}".formatted(slug), "title", "required"),
                Arguments.of("title of 121 characters",
                        "{\"title\": \"%s\", %s}".formatted("a".repeat(121), slug), "title", "too-long"),
                Arguments.of("no slug", "{%s}".formatted(title), "slug", "required"),
                Arguments.of("blank slug", "{%s, \"slug\": \"  \"}".formatted(title), "slug", "required"),
                Arguments.of("slug with upper case", "{%s, \"slug\": \"Estado-com-hooks\"}".formatted(title), "slug",
                        "invalid-format"),
                Arguments.of("slug with an accent", "{%s, \"slug\": \"introdução\"}".formatted(title), "slug",
                        "invalid-format"),
                Arguments.of("slug with two hyphens in a row", "{%s, \"slug\": \"estado--hooks\"}".formatted(title),
                        "slug", "invalid-format"),
                Arguments.of("slug of 81 characters", "{%s, \"slug\": \"%s\"}".formatted(title, "a".repeat(81)),
                        "slug", "too-long"));
    }

    @Test
    void acceptsTheLongestTitlesAndSlug() {
        long courseId = createCourse();
        String title = "á".repeat(120);
        String slug = "a".repeat(80);

        long module = idOf(addModule(courseId, title));
        assertThat(renameModule(module, title)).hasStatusOk().bodyJson().extractingPath("$.title").isEqualTo(title);
        MvcTestResult lesson = addLesson(module, title, slug);

        assertThat(lesson).hasStatus(HttpStatus.CREATED).bodyJson().isLenientlyEqualTo("""
                {"title": "%s", "slug": "%s"}""".formatted(title, slug));
        assertThat(editLesson(idOf(lesson), title, slug)).hasStatusOk();
    }

    private void assertInvalidRequest(MvcTestResult result, String path, String field, String code) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "One or more fields are invalid.",
                          "instance": "%s",
                          "timestamp": "%s",
                          "errors": [{"field": "%s", "code": "%s"}]
                        }""".formatted(path, clock.instant(), field, code));
    }

    private void assertCourseNotFound(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.NOT_FOUND, "course-not-found", "Course not found",
                "The Course does not exist.");
    }

    private void assertModuleNotFound(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.NOT_FOUND, "module-not-found", "Module not found",
                "No Module has this id.");
    }

    private void assertLessonNotFound(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.NOT_FOUND, "lesson-not-found", "Lesson not found",
                "No Lesson has this id.");
    }

    private void assertLessonSlugTaken(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.CONFLICT, "lesson-slug-taken", "Lesson slug taken",
                "Another Lesson of this Course already has this slug.");
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

    private long createCourse() {
        MvcTestResult created = post("/v1/admin/courses", """
                {"slug": "curso-%s", "title": "Backend com Node.js"}""".formatted(UUID.randomUUID()));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return idOf(created);
    }

    private MvcTestResult addModule(long courseId, String title) {
        return post("/v1/admin/courses/%d/modules".formatted(courseId), """
                {"title": "%s"}""".formatted(title));
    }

    private MvcTestResult renameModule(long moduleId, String title) {
        return put("/v1/admin/modules/" + moduleId, """
                {"title": "%s"}""".formatted(title));
    }

    private MvcTestResult deleteModule(long moduleId) {
        return delete("/v1/admin/modules/" + moduleId);
    }

    private MvcTestResult addLesson(long moduleId, String title, String slug) {
        return post("/v1/admin/modules/%d/lessons".formatted(moduleId), """
                {"title": "%s", "slug": "%s"}""".formatted(title, slug));
    }

    private MvcTestResult editLesson(long lessonId, String title, String slug) {
        return put("/v1/admin/lessons/" + lessonId, """
                {"title": "%s", "slug": "%s"}""".formatted(title, slug));
    }

    private MvcTestResult deleteLesson(long lessonId) {
        return delete("/v1/admin/lessons/" + lessonId);
    }

    private MvcTestResult publish(long lessonId) {
        return put("/v1/admin/lessons/%d/status".formatted(lessonId), "{\"status\": \"PUBLISHED\"}");
    }

    private AdminCourses courses() {
        return new AdminCourses(mvc, token, storedVideos);
    }

    private MvcTestResult outline(long courseId) {
        return get("/v1/admin/courses/%d/outline".formatted(courseId));
    }

    private MvcTestResult putOutline(long courseId, String outline) {
        return put("/v1/admin/courses/%d/outline".formatted(courseId), outline);
    }

    private MvcTestResult get(String path) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult delete(String path) {
        return mvc.delete().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult post(String path, String body) {
        return mvc.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult put(String path, String body) {
        return mvc.put().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static long idOf(MvcTestResult result) {
        return ((Number) JsonPath.read(body(result), "$.id")).longValue();
    }
}
