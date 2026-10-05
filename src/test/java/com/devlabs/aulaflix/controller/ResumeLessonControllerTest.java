package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.LearningApi;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * "Continuar de onde parou": the visits the Lesson page records when it mounts, the Resume lesson they lead to, the
 * highlighted Course, and "Meus cursos" ordered by the last visit.
 */
class ResumeLessonControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    private AdminCourses courses;

    private AdminEnrollments enrollments;

    private BffApi bff;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(email, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        enrollments = new AdminEnrollments(mvc, adminToken);
        bff = new BffApi(mvc);
    }

    @Test
    void resumesAtTheFirstUnfinishedPublishedLessonBeforeAnyVisit() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(course.id());

        assertThat(learning.enrollments()).hasStatusOk().bodyJson().extractingPath("$.items[0].resumeLesson")
                .isEqualTo(Map.of("id", Math.toIntExact(course.freeLesson()), "slug", "o-que-e-uma-api",
                        "number", 1, "title", "O que é uma API"));

        learning.completed(course.freeLesson());

        assertResumeLesson(learning, course, course.express());
    }

    @Test
    void resumesAtTheLastLessonOpenedWhileItIsNotCompleted() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(course.id());

        assertThat(learning.visit(course.status())).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(learning.enrollments()).bodyJson().extractingPath("$.items[0].resumeLesson")
                .isEqualTo(Map.of("id", Math.toIntExact(course.status()), "slug", "status-e-erros", "number", 3,
                        "title", "Status e erros"));
        assertThat(learning.enrollment(course.id())).hasStatusOk().bodyJson()
                .extractingPath("$.resumeLesson").isEqualTo(Map.of("id", Math.toIntExact(course.status()),
                        "slug", "status-e-erros", "number", 3, "title", "Status e erros"));

        learning.visited(course.express());

        assertResumeLesson(learning, course, course.express());
    }

    @Test
    void resumesAfterTheLastLessonOpenedOnceItIsCompletedElseAtTheFirstUnfinishedOne() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(course.id());
        learning.visited(course.express());

        learning.completed(course.express());

        assertResumeLesson(learning, course, course.status());

        learning.completed(course.status());

        assertResumeLesson(learning, course, course.freeLesson());
    }

    @Test
    void resumesAtTheFirstPublishedLessonWithEveryLessonDone() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(course.id());
        learning.visited(course.status());
        courses.linkVideo(course.emBreve(), "three-seconds.mp4");
        courses.publish(course.emBreve());

        learning.completed(course.freeLesson(), course.express(), course.status(), course.emBreve());

        assertResumeLesson(learning, course, course.freeLesson());
    }

    @Test
    void followsTheOutlinesNewOrderOnceItIsReordered() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(course.id());
        learning.visited(course.express());
        learning.completed(course.express());
        long fundamentos = ((Number) JsonPath.read(courses.outline(course.id()), "$[0].moduleId")).longValue();
        long routes = ((Number) JsonPath.read(courses.outline(course.id()), "$[1].moduleId")).longValue();

        courses.putOutline(course.id(), """
                [{"moduleId": %d, "lessonIds": [%d, %d, %d]}, {"moduleId": %d, "lessonIds": [%d]}]"""
                .formatted(routes, course.status(), course.emBreve(), course.express(), fundamentos,
                        course.freeLesson()));

        assertThat(learning.enrollments()).bodyJson().extractingPath("$.items[0].resumeLesson")
                .isEqualTo(Map.of("id", Math.toIntExact(course.freeLesson()), "slug", "o-que-e-uma-api",
                        "number", 4, "title", "O que é uma API"));

        learning.completed(course.freeLesson());

        assertThat(learning.enrollments()).bodyJson().extractingPath("$.items[0].resumeLesson")
                .isEqualTo(Map.of("id", Math.toIntExact(course.status()), "slug", "status-e-erros", "number", 1,
                        "title", "Status e erros"));
    }

    @Test
    void ordersMeusCursosByTheLastVisitThenTheUnvisitedByTheirStart() {
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        List<Course> enrolled = List.of(onSaleCourse(newSlug()), onSaleCourse(newSlug()), onSaleCourse(newSlug()),
                onSaleCourse(newSlug()));
        for (Course course : enrolled) {
            enrollments.granted(email, course.id());
            later();
        }
        learning.visited(enrolled.get(1).express());
        later();
        learning.visited(enrolled.get(3).express());

        assertThat(courseIds(learning.enrollments())).containsExactly(enrolled.get(3).id(), enrolled.get(1).id(),
                enrolled.get(0).id(), enrolled.get(2).id());

        later();
        learning.visited(enrolled.get(1).status());

        assertThat(courseIds(learning.enrollments())).containsExactly(enrolled.get(1).id(), enrolled.get(3).id(),
                enrolled.get(0).id(), enrolled.get(2).id());
    }

    @Test
    void highlightsTheMostRecentlyVisitedCourseWithAnUnfinishedPublishedLesson() {
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        Course inProgress = onSaleCourse(newSlug());
        Course caughtUp = onSaleCourse(newSlug());
        long finished = courses.onSale(newSlug());
        long finishedLesson = courses.freeLessonOf(finished);
        Course unvisited = onSaleCourse(newSlug());
        long comingSoon = courses.announced(newSlug());
        for (long course : List.of(inProgress.id(), caughtUp.id(), finished, unvisited.id(), comingSoon)) {
            enrollments.granted(email, course);
        }
        learning.visited(inProgress.express());
        later();
        learning.visited(caughtUp.express());
        learning.completed(caughtUp.freeLesson(), caughtUp.express(), caughtUp.status());
        later();
        learning.visited(finishedLesson);
        learning.completed(finishedLesson);

        assertThat(learning.enrollments()).hasStatusOk().bodyJson().extractingPath("$.highlightedCourseId")
                .isEqualTo(Math.toIntExact(inProgress.id()));

        learning.completed(inProgress.freeLesson(), inProgress.express(), inProgress.status());

        assertThat(learning.enrollments()).hasStatusOk().bodyJson().doesNotHavePath("$.highlightedCourseId");
    }

    @Test
    void highlightsNoCourseBeforeAnyVisit() {
        LearningApi learning = enrolledStudent(onSaleCourse(newSlug()).id());

        assertThat(learning.enrollments()).hasStatusOk().bodyJson().doesNotHavePath("$.highlightedCourseId");
    }

    @Test
    void neverHighlightsAComingSoonEnrollmentNorGivesItAResumeLesson() {
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        enrollments.granted(email, courses.announced(newSlug()));

        assertThat(learning.enrollments()).hasStatusOk().bodyJson()
                .hasPath("$.items[0].course")
                .doesNotHavePath("$.highlightedCourseId")
                .doesNotHavePath("$.items[0].resumeLesson");
    }

    @Test
    void recordsNoVisitOnAnyGet() {
        Course course = onSaleCourse(newSlug());
        String email = StudentApi.newEmail();
        String token = new StudentApi(bff).signedUp(email, PASSWORD);
        LearningApi learning = new LearningApi(bff, token);
        enrollments.granted(email, course.id());

        assertThat(learning.enrollments()).hasStatusOk();
        assertThat(learning.enrollment(course.id())).hasStatusOk();
        assertThat(bff.get("/v1/lessons/%d/playback".formatted(course.status()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()).hasStatusOk();

        assertResumeLesson(learning, course, course.freeLesson());
        assertThat(learning.enrollments()).bodyJson().doesNotHavePath("$.highlightedCourseId");
    }

    @Test
    void refusesToRecordAVisitToAnEmBreveLessonAsAnUnknownOne() {
        Course course = onSaleCourse(newSlug());

        assertLessonNotFound(enrolledStudent(course.id()).visit(course.emBreve()));
    }

    @ParameterizedTest
    @ValueSource(longs = {999999999999L, 0, -1})
    void refusesToRecordAVisitToAnUnknownLesson(long lessonId) {
        assertLessonNotFound(enrolledStudent(onSaleCourse(newSlug()).id()).visit(lessonId));
    }

    @Test
    void refusesToRecordAVisitToALessonOfACourseNotOnSaleAsAnUnknownOne() {
        long draft = courses.draft(newSlug());
        long draftLesson = courses.addPublishedLesson(courses.addModule(draft, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        long comingSoon = courses.announced(newSlug());
        long comingSoonLesson = courses.addPublishedLesson(courses.addModule(comingSoon, "Fundamentos"),
                "O que é uma API", "o-que-e-uma-api", "three-seconds.mp4");
        LearningApi learning = enrolledStudent(comingSoon);

        assertLessonNotFound(learning.visit(draftLesson));
        assertLessonNotFound(learning.visit(comingSoonLesson));
    }

    @Test
    void refusesToRecordAVisitWithoutAnActiveEnrollmentInTheLessonsCourse() {
        Course course = onSaleCourse(newSlug());
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));

        assertEnrollmentRequired(learning.visit(course.freeLesson()));

        enrollments.ended(enrollments.granted(email, course.id()));

        assertEnrollmentRequired(learning.visit(course.express()));
    }

    @Test
    void refusesAVisitWithoutALessonIdOrWithOneThatIsNotANumber() {
        LearningApi learning = enrolledStudent(onSaleCourse(newSlug()).id());

        assertInvalidLessonId(learning.visitWith("{}"), "required");
        assertInvalidLessonId(learning.visitWith("{\"lessonId\": null}"), "required");
        assertInvalidLessonId(learning.visit("abc"), "invalid-format");
        assertInvalidLessonId(learning.visit(1.5), "invalid-format");
    }

    @Test
    void forbidsAnAdminToRecordAVisit() {
        Course course = onSaleCourse(newSlug());
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        LearningApi admin = new LearningApi(bff, new AdminApi(mvc).sessionToken(email, PASSWORD));

        assertProblem(admin.visit(course.express()), HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "This session's role may not do this.");
    }

    @Test
    void asksForASessionToRecordAVisit() {
        Course course = onSaleCourse(newSlug());

        MvcTestResult result = bff.post(LearningApi.LESSON_VISITS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"lessonId\": %d}".formatted(course.express())).exchange();

        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertProblem(result, HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated",
                "This needs a valid session token, sent as Authorization: Bearer.");
    }

    /** A new Student, signed in, with an active Enrollment in the Course. */
    private LearningApi enrolledStudent(long course) {
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        enrollments.granted(email, course);
        return learning;
    }

    /** Moves the clock a minute on, so that the next visit or grant comes strictly after the last one. */
    private void later() {
        clock.set(clock.instant().plusSeconds(60));
    }

    /** The Resume lesson of the Student's only Enrollment, in a Course of {@link #onSaleCourse}, by its id. */
    private static void assertResumeLesson(LearningApi learning, Course course, long lessonId) {
        assertThat(learning.enrollments()).hasStatusOk().bodyJson().extractingPath("$.items[0].resumeLesson.id")
                .isEqualTo(Math.toIntExact(lessonId));
        assertThat(learning.enrollment(course.id())).hasStatusOk().bodyJson().extractingPath("$.resumeLesson.id")
                .isEqualTo(Math.toIntExact(lessonId));
    }

    private static List<Long> courseIds(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        List<Number> ids = JsonPath.read(AdminApi.body(result), "$.items[*].course.id");
        return ids.stream().map(Number::longValue).toList();
    }

    private void assertLessonNotFound(MvcTestResult result) {
        assertProblem(result, HttpStatus.NOT_FOUND, "lesson-not-found", "Lesson not found", "No Lesson has this id.");
    }

    private void assertEnrollmentRequired(MvcTestResult result) {
        assertProblem(result, HttpStatus.CONFLICT, "enrollment-required", "Enrollment required",
                "This needs an active Enrollment in the Course.");
    }

    private void assertInvalidLessonId(MvcTestResult result, String code) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "status": 400,
                          "instance": "%s",
                          "errors": [{"field": "lessonId", "code": "%s"}]
                        }""".formatted(LearningApi.LESSON_VISITS, code));
    }

    /** The whole ProblemDetail of a refusal of a visit that carries no extension. */
    private void assertProblem(MvcTestResult result, HttpStatus status, String name, String title, String detail) {
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
                        }""".formatted(name, title, status.value(), detail, LearningApi.LESSON_VISITS,
                        clock.instant()));
    }

    /**
     * An On sale Course of four Lessons: the Free lesson in "Fundamentos", then two published Lessons and one "Em breve"
     * in "Rotas e respostas".
     */
    private Course onSaleCourse(String slug) {
        long id = courses.onSale(slug);
        long routes = courses.addModule(id, "Rotas e respostas");
        long express = courses.addPublishedLesson(routes, "Rotas no Express", "rotas-no-express", "three-seconds.mp4");
        long status = courses.addPublishedLesson(routes, "Status e erros", "status-e-erros", "three-seconds.mp4");
        long middlewares = courses.addLesson(routes, "Middlewares", "middlewares");
        return new Course(id, courses.freeLessonOf(id), express, status, middlewares);
    }

    private record Course(long id, long freeLesson, long express, long status, long emBreve) {
    }
}
