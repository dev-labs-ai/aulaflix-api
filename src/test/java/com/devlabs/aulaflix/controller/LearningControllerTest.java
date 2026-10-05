package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * "Meus cursos" and the Lesson marks, as the BFF calls them for a signed-in Student: the active Enrollments, each with
 * its Course and Progress, and the marks that move the counts and the standing.
 */
class LearningControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

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
    void listsAnActiveEnrollmentWithItsCourseAndProgressBeforeAnyMark() {
        String slug = newSlug();
        Course course = onSaleCourse(slug);
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        enrollments.granted(email, course.id());

        assertThat(learning.enrollments()).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "items": [{
                            "course": {
                              "id": %d, "slug": "%s", "title": "Backend com Node.js", "area": "BACKEND",
                              "icon": "SERVER", "tone": "CORAL", "status": "ON_SALE"
                            },
                            "progress": {
                              "completed": 0, "published": 3, "total": 4, "percent": 0, "standing": "NOT_STARTED"
                            }
                          }]
                        }""".formatted(course.id(), slug));
    }

    @Test
    void movesTheCountsAndTheStandingWithEachMarkAndTakesARepeatedMarkOnce() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(course.id());

        assertThat(learning.complete(course.express())).hasStatus(HttpStatus.NO_CONTENT);
        assertProgress(learning, 1, 25, "IN_PROGRESS");

        assertThat(learning.complete(course.express())).hasStatus(HttpStatus.NO_CONTENT);
        assertProgress(learning, 1, 25, "IN_PROGRESS");

        learning.completed(course.freeLesson(), course.status());
        assertProgress(learning, 3, 75, "CAUGHT_UP");

        assertThat(learning.uncomplete(course.express())).hasStatus(HttpStatus.NO_CONTENT);
        assertProgress(learning, 2, 50, "IN_PROGRESS");

        assertThat(learning.uncomplete(course.express())).hasStatus(HttpStatus.NO_CONTENT);
        assertProgress(learning, 2, 50, "IN_PROGRESS");

        learning.uncomplete(course.freeLesson());
        learning.uncomplete(course.status());
        assertProgress(learning, 0, 0, "NOT_STARTED");
    }

    @Test
    void finishesTheCourseOnlyOnceTheLastEmBreveLessonIsPublishedAndCompleted() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(course.id());
        learning.completed(course.freeLesson(), course.express(), course.status());
        courses.linkVideo(course.emBreve(), "three-seconds.mp4");
        courses.publish(course.emBreve());

        assertThat(learning.enrollments()).bodyJson().extractingPath("$.items[0].progress").isEqualTo(Map.of(
                "completed", 3, "published", 4, "total", 4, "percent", 75, "standing", "IN_PROGRESS"));

        learning.completed(course.emBreve());

        assertThat(learning.enrollments()).bodyJson().extractingPath("$.items[0].progress").isEqualTo(Map.of(
                "completed", 4, "published", 4, "total", 4, "percent", 100, "standing", "FINISHED"));
    }

    @Test
    void roundsThePercentDownFrom4Of12To33() {
        long course = courses.onSale(newSlug());
        long module = courses.addModule(course, "Rotas e respostas");
        long[] published = {courses.freeLessonOf(course),
                courses.addPublishedLesson(module, "Rotas no Express", "rotas-no-express", "three-seconds.mp4"),
                courses.addPublishedLesson(module, "Status e erros", "status-e-erros", "three-seconds.mp4"),
                courses.addPublishedLesson(module, "Middlewares", "middlewares", "three-seconds.mp4")};
        for (int lesson = 1; lesson <= 8; lesson++) {
            courses.addLesson(module, "Aula " + lesson, "aula-" + lesson);
        }
        LearningApi learning = enrolledStudent(course);

        learning.completed(published);

        assertThat(learning.enrollments()).bodyJson().extractingPath("$.items[0].progress").isEqualTo(Map.of(
                "completed", 4, "published", 4, "total", 12, "percent", 33, "standing", "CAUGHT_UP"));
    }

    @Test
    void readsAnEnrollmentWithItsProgressAndTheCompletedLessons() {
        String slug = newSlug();
        Course course = onSaleCourse(slug);
        LearningApi learning = enrolledStudent(course.id());
        learning.completed(course.status(), course.freeLesson());

        assertThat(learning.enrollment(course.id())).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "course": {
                            "id": %d, "slug": "%s", "title": "Backend com Node.js", "area": "BACKEND",
                            "icon": "SERVER", "tone": "CORAL", "status": "ON_SALE"
                          },
                          "progress": {
                            "completed": 2, "published": 3, "total": 4, "percent": 50, "standing": "IN_PROGRESS"
                          },
                          "completedLessonIds": [%d, %d]
                        }""".formatted(course.id(), slug, course.freeLesson(), course.status()));
    }

    @Test
    void readsAnEnrollmentWithNoLessonCompletedYet() {
        Course course = onSaleCourse(newSlug());

        assertThat(enrolledStudent(course.id()).enrollment(course.id())).hasStatusOk().bodyJson()
                .extractingPath("$.completedLessonIds").asArray().isEmpty();
    }

    /** Progress belongs to the Student: another Student's marks in the same Course count for nothing. */
    @Test
    void countsOnlyTheStudentsOwnMarks() {
        Course course = onSaleCourse(newSlug());
        LearningApi other = enrolledStudent(course.id());
        other.completed(course.freeLesson(), course.express());
        LearningApi learning = enrolledStudent(course.id());
        learning.completed(course.status());

        assertThat(idsAt(learning.enrollment(course.id()), "$.completedLessonIds")).containsExactly(course.status());
        assertProgress(learning, 1, 25, "IN_PROGRESS");
    }

    @Test
    void answersACourseWithoutAnActiveEnrollmentAsAnUnknownEnrollment() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(onSaleCourse(newSlug()).id());

        assertEnrollmentNotFound(learning.enrollment(course.id()), course.id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999", "0", "-1", "abc", "1.5", "99999999999999999999"})
    void answersACourseIdOfAnyShapeLikeAnUnknownEnrollment(String courseId) {
        LearningApi learning = enrolledStudent(onSaleCourse(newSlug()).id());

        assertEnrollmentNotFound(learning.enrollment(courseId), courseId);
    }

    @Test
    void listsOnlyActiveEnrollmentsOldestFirst() {
        Course first = onSaleCourse(newSlug());
        Course ended = onSaleCourse(newSlug());
        Course last = onSaleCourse(newSlug());
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        enrollments.granted(email, first.id());
        clock.set(clock.instant().plusSeconds(60));
        enrollments.ended(enrollments.granted(email, ended.id()));
        clock.set(clock.instant().plusSeconds(60));
        enrollments.granted(email, last.id());

        assertThat(idsAt(learning.enrollments(), "$.items[*].course.id")).containsExactly(first.id(), last.id());
    }

    @Test
    void listsNothingForAStudentWithoutEnrollments() {
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));

        assertThat(learning.enrollments()).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"items\": []}");
    }

    /** Progress outlives the Enrollment: unreachable while none is active, intact once a new one starts. */
    @Test
    void hidesTheProgressOnceTheEnrollmentEndsAndBringsItBackIntactWithANewOne() {
        Course course = onSaleCourse(newSlug());
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        long enrollment = enrollments.granted(email, course.id());
        learning.completed(course.freeLesson(), course.express());

        enrollments.ended(enrollment);

        assertEnrollmentNotFound(learning.enrollment(course.id()), course.id());
        assertThat(learning.enrollments()).bodyJson().isStrictlyEqualTo("{\"items\": []}");
        assertEnrollmentRequired(learning.complete(course.status()), course.status());
        assertEnrollmentRequired(learning.uncomplete(course.express()), course.express());

        enrollments.granted(email, course.id());

        assertThat(idsAt(learning.enrollment(course.id()), "$.completedLessonIds"))
                .containsExactly(course.freeLesson(), course.express());
        assertProgress(learning, 2, 50, "IN_PROGRESS");
    }

    @Test
    void refusesToMarkAnEmBreveLessonAsAnUnknownOne() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(course.id());

        assertLessonNotFound(learning.complete(course.emBreve()), course.emBreve());
        assertLessonNotFound(learning.uncomplete(course.emBreve()), course.emBreve());
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999", "0", "-1", "abc", "1.5", "99999999999999999999"})
    void refusesToMarkALessonIdOfAnyShapeAsAnUnknownLesson(String lessonId) {
        LearningApi learning = enrolledStudent(onSaleCourse(newSlug()).id());

        assertLessonNotFound(learning.complete(lessonId), lessonId);
        assertLessonNotFound(learning.uncomplete(lessonId), lessonId);
    }

    @Test
    void refusesToMarkALessonOfADraftAsAnUnknownOne() {
        long course = courses.draft(newSlug());
        long lesson = courses.addPublishedLesson(courses.addModule(course, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));

        assertLessonNotFound(learning.complete(lesson), lesson);
    }

    @Test
    void refusesToMarkWithoutAnActiveEnrollmentInTheLessonsCourse() {
        Course course = onSaleCourse(newSlug());
        LearningApi learning = enrolledStudent(onSaleCourse(newSlug()).id());

        assertEnrollmentRequired(learning.complete(course.express()), course.express());
        assertEnrollmentRequired(learning.complete(course.freeLesson()), course.freeLesson());
        assertEnrollmentRequired(learning.uncomplete(course.express()), course.express());
    }

    /** A Coming soon Course's Lessons open at the launch: until then, the Enrollment has no Progress and no marks. */
    @Test
    void listsAComingSoonEnrollmentWithoutProgressAndTakesNoMarksUntilTheLaunch() {
        String slug = newSlug();
        long course = courses.announced(slug);
        long module = courses.addModule(course, "Fundamentos");
        long freeLesson = courses.addPublishedLesson(module, "O que é uma API", "o-que-e-uma-api",
                "three-seconds.mp4");
        LearningApi learning = enrolledStudent(course);

        assertThat(learning.enrollments()).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {
                  "items": [{
                    "course": {
                      "id": %d, "slug": "%s", "title": "Backend com Node.js", "area": "BACKEND",
                      "icon": "SERVER", "tone": "CORAL", "status": "COMING_SOON"
                    }
                  }]
                }""".formatted(course, slug));
        assertThat(learning.enrollment(course)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {
                  "course": {
                    "id": %d, "slug": "%s", "title": "Backend com Node.js", "area": "BACKEND",
                    "icon": "SERVER", "tone": "CORAL", "status": "COMING_SOON"
                  }
                }""".formatted(course, slug));
        assertLessonNotFound(learning.complete(freeLesson), freeLesson);

        courses.putDocument(course, AdminCourses.fullDocument(slug, freeLesson));
        courses.moveTo(course, "ON_SALE");

        learning.completed(freeLesson);
        assertThat(learning.enrollments()).bodyJson().extractingPath("$.items[0].progress").isEqualTo(Map.of(
                "completed", 1, "published", 1, "total", 1, "percent", 100, "standing", "FINISHED"));
    }

    /** No N+1: listing four Enrollments, of both kinds, takes as many statements as listing one. */
    @Test
    void listsAnyNumberOfEnrollmentsInTheSameNumberOfStatements() {
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        Course first = onSaleCourse(newSlug());
        enrollments.granted(email, first.id());
        learning.completed(first.freeLesson());
        long withOne = statementsToList(learning);
        for (Course course : List.of(onSaleCourse(newSlug()), onSaleCourse(newSlug()))) {
            enrollments.granted(email, course.id());
            learning.completed(course.express());
        }
        enrollments.granted(email, courses.announced(newSlug()));

        long withFour = statementsToList(learning);

        assertThat(learning.enrollments()).bodyJson().extractingPath("$.items").asArray().hasSize(4);
        assertThat(withOne).isPositive();
        assertThat(withFour).isEqualTo(withOne);
    }

    @Test
    void forbidsAnAdminEveryEndpoint() {
        Course course = onSaleCourse(newSlug());
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        LearningApi admin = new LearningApi(bff, new AdminApi(mvc).sessionToken(email, PASSWORD));

        assertForbidden(admin.enrollments(), "/v1/account/enrollments");
        assertForbidden(admin.enrollment(course.id()), "/v1/account/enrollments/" + course.id());
        assertForbidden(admin.complete(course.express()), LearningApi.completedLesson(course.express()));
        assertForbidden(admin.uncomplete(course.express()), LearningApi.completedLesson(course.express()));
    }

    @Test
    void asksForASessionOnEveryEndpoint() {
        Course course = onSaleCourse(newSlug());

        assertUnauthenticated(bff.get("/v1/account/enrollments").exchange(), "/v1/account/enrollments");
        assertUnauthenticated(bff.get("/v1/account/enrollments/" + course.id()).exchange(),
                "/v1/account/enrollments/" + course.id());
        assertUnauthenticated(bff.put(LearningApi.completedLesson(course.express())).exchange(),
                LearningApi.completedLesson(course.express()));
        assertUnauthenticated(bff.delete(LearningApi.completedLesson(course.express())).exchange(),
                LearningApi.completedLesson(course.express()));
    }

    /** A new Student, signed in, with an active Enrollment in the Course. */
    private LearningApi enrolledStudent(long course) {
        String email = StudentApi.newEmail();
        LearningApi learning = new LearningApi(bff, new StudentApi(bff).signedUp(email, PASSWORD));
        enrollments.granted(email, course);
        return learning;
    }

    /** The Progress of the Student's only Enrollment, in a Course of {@link #onSaleCourse}. */
    private static void assertProgress(LearningApi learning, int completed, int percent, String standing) {
        assertThat(learning.enrollments()).hasStatusOk().bodyJson().extractingPath("$.items[0].progress")
                .isEqualTo(Map.of("completed", completed, "published", 3, "total", 4, "percent", percent,
                        "standing", standing));
    }

    /** How many JDBC statements the whole request prepares, the session's look-up included. */
    private long statementsToList(LearningApi learning) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            assertThat(learning.enrollments()).hasStatusOk();
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    /** The ids at the path of a 200's body, in order. */
    private static List<Long> idsAt(MvcTestResult result, String path) {
        assertThat(result).hasStatusOk();
        List<Number> ids = JsonPath.read(AdminApi.body(result), path);
        return ids.stream().map(Number::longValue).toList();
    }

    private void assertEnrollmentNotFound(MvcTestResult result, Object courseId) {
        assertProblem(result, "/v1/account/enrollments/" + courseId, HttpStatus.NOT_FOUND, "enrollment-not-found",
                "Enrollment not found", "No Enrollment has this id.");
    }

    private void assertLessonNotFound(MvcTestResult result, Object lessonId) {
        assertProblem(result, LearningApi.completedLesson(lessonId), HttpStatus.NOT_FOUND, "lesson-not-found",
                "Lesson not found", "No Lesson has this id.");
    }

    private void assertEnrollmentRequired(MvcTestResult result, long lessonId) {
        assertProblem(result, LearningApi.completedLesson(lessonId), HttpStatus.CONFLICT, "enrollment-required",
                "Enrollment required", "This needs an active Enrollment in the Course.");
    }

    private void assertForbidden(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "This session's role may not do this.");
    }

    private void assertUnauthenticated(MvcTestResult result, String path) {
        assertThat(result).hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        assertProblem(result, path, HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated",
                "This needs a valid session token, sent as Authorization: Bearer.");
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
