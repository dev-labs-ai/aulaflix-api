package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.AdminEnrollments.fields;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AdminEnrollments;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredAccounts;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.dto.AccountSummary;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.jayway.jsonpath.JsonPath;

/**
 * Enrollments the Admin grants and ends by hand, with a note, as the Student's playback then shows them. A manual
 * Enrollment opens every published Lesson of its Course once the Course is On sale.
 */
class AdminEnrollmentControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String NOTE = "Chargeback ganho no pedido K7M2Q9XA.";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    private AccountSummary admin;

    private AdminCourses courses;

    private AdminEnrollments enrollments;

    private BffApi bff;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        admin = accounts.createAdmin(email, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(email, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        enrollments = new AdminEnrollments(mvc, adminToken);
        bff = new BffApi(mvc);
    }

    @Test
    void grantsAnEnrollmentThatPlaysTheCoursesOtherPublishedLessons() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String email = StudentApi.newEmail();
        String token = new StudentApi(bff).signedUp(email, PASSWORD);

        MvcTestResult grant = enrollments.grant(email, course, NOTE);

        assertThat(grant).hasStatus(HttpStatus.CREATED);
        MvcTestResult playback = playback(lesson, token);
        assertThat(playback).hasStatusOk().hasHeader(HttpHeaders.CACHE_CONTROL, "no-store")
                .bodyJson().extractingPath("$").asMap().containsOnlyKeys("url", "expiresAt");
        assertThat(JsonPath.<String>read(body(playback), "$.url")).contains("/lessons/%d/".formatted(lesson));
    }

    @Test
    void answersTheGrantWithTheNewEnrollmentAndItsAddress() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);

        MvcTestResult grant = enrollments.grant(email, course, "  " + NOTE + "\n");

        long id = ((Number) JsonPath.read(body(grant), "$.id")).longValue();
        assertThat(grant).hasStatus(HttpStatus.CREATED)
                .hasContentType(MediaType.APPLICATION_JSON)
                .hasHeader(HttpHeaders.LOCATION, "/v1/admin/enrollments/" + id)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "id": %d,
                          "status": "ACTIVE",
                          "student": {"id": %d, "email": "%s", "name": "Bia"},
                          "course": {"id": %d, "slug": "%s", "title": "Backend com Node.js", "status": "ON_SALE"},
                          "startedAt": "%s",
                          "origin": "MANUAL",
                          "grantedBy": {"id": %d, "email": "%s", "name": "Ana"},
                          "grantNote": "%s"
                        }""".formatted(id, studentIdOf(email), email, course, slug,
                        clock.instant().truncatedTo(ChronoUnit.MICROS), admin.id(), admin.email(), NOTE));
    }

    /** The Admin tells the Student: the only email they ever got is the welcome one that came with their Account. */
    @Test
    void sendsNoEmailForAGrant() {
        long course = courses.onSale(newSlug());
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);

        assertThat(enrollments.grant(email, course, NOTE)).hasStatus(HttpStatus.CREATED);
        outbox.drain();

        assertThat(mailpit.to(email)).singleElement().extracting(Mailpit.Email::subject)
                .isEqualTo("Boas-vindas à AulaFlix: confirme seu email");
    }

    @Test
    void findsTheStudentByTheirEmailTrimmedAndLowerCased() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String email = StudentApi.newEmail();
        String token = new StudentApi(bff).signedUp(email, PASSWORD);

        assertThat(enrollments.grant(" " + email.toUpperCase(Locale.ROOT) + " ", course, NOTE))
                .hasStatus(HttpStatus.CREATED);
        assertThat(playback(lesson, token)).hasStatusOk();
    }

    /** The Lessons open at the launch: until then, playback answers as for any Course not On sale. */
    @Test
    void grantsAnEnrollmentInAComingSoonCourseWhoseLessonsStayUnplayableUntilTheLaunch() {
        String slug = newSlug();
        long course = courses.announced(slug);
        long lesson = courses.addPublishedLesson(courses.addModule(course, "Fundamentos"), "O que é uma API",
                "o-que-e-uma-api", "three-seconds.mp4");
        String email = StudentApi.newEmail();
        String token = new StudentApi(bff).signedUp(email, PASSWORD);

        MvcTestResult grant = enrollments.grant(email, course, NOTE);

        assertThat(grant).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.course.status")
                .isEqualTo("COMING_SOON");
        assertProblem(playback(lesson, token), "/v1/lessons/%d/playback".formatted(lesson), HttpStatus.NOT_FOUND,
                "lesson-not-found", "Lesson not found", "No Lesson has this id.");
    }

    @Test
    void refusesAnEmailWithNoAccount() {
        long course = courses.onSale(newSlug());

        assertStudentAccountRequired(enrollments.grant(StudentApi.newEmail(), course, NOTE));
    }

    /** An Admin is never a Student, so their Account takes no Enrollment. */
    @Test
    void refusesAnAdminsEmail() {
        long course = courses.onSale(newSlug());

        assertStudentAccountRequired(enrollments.grant(admin.email(), course, NOTE));
    }

    /** A Draft is invisible, and may be deleted. */
    @Test
    void refusesADraft() {
        long draft = courses.completeDraft(newSlug());
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);

        assertCourseNotEnrollable(enrollments.grant(email, draft, NOTE));
    }

    @Test
    void refusesAnUnknownCourse() {
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);

        assertCourseNotEnrollable(enrollments.grant(email, 999_999_999_999L, NOTE));
    }

    @ParameterizedTest
    @MethodSource("idsOfAnyShape")
    void answersACourseIdOfAnyShapeLikeAnUnknownCourseOnAGrant(Object courseId) {
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);

        assertCourseNotEnrollable(enrollments.grant(Map.of("email", email, "courseId", courseId, "note", NOTE)));
    }

    @Test
    void refusesASecondActiveEnrollmentInTheCourse() {
        long course = courses.onSale(newSlug());
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);
        enrollments.granted(email, course);

        assertProblem(enrollments.grant(email, course, NOTE), "/v1/admin/enrollments", HttpStatus.CONFLICT,
                "already-enrolled", "Already enrolled", "The Student already has an active Enrollment in this Course.");
    }

    @Test
    void grantsTheSameStudentAnEnrollmentInAnotherCourse() {
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);
        enrollments.granted(email, courses.onSale(newSlug()));

        assertThat(enrollments.grant(email, courses.onSale(newSlug()), NOTE)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void takesANoteOf500Characters() {
        long course = courses.onSale(newSlug());
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);

        assertThat(enrollments.grant(email, course, "é".repeat(500))).hasStatus(HttpStatus.CREATED)
                .bodyJson().extractingPath("$.grantNote").isEqualTo("é".repeat(500));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidGrants")
    void refusesAnInvalidFieldWithItsCode(String description, Map<String, Object> body, String field, String code) {
        assertThat(enrollments.grant(body)).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "status": 400,
                          "instance": "/v1/admin/enrollments",
                          "errors": [{"field": "%s", "code": "%s"}]
                        }""".formatted(field, code));
    }

    static Stream<Arguments> invalidGrants() {
        String email = "bia@aulaflix.com.br";
        return Stream.of(
                Arguments.of("no note", fields("email", email, "courseId", 1, "note", null), "note", "required"),
                Arguments.of("blank note", fields("email", email, "courseId", 1, "note", " \n "), "note",
                        "required"),
                Arguments.of("note of 501 characters", fields("email", email, "courseId", 1, "note", "a".repeat(501)),
                        "note", "too-long"),
                Arguments.of("no email", fields("email", null, "courseId", 1, "note", NOTE), "email", "required"),
                Arguments.of("blank email", fields("email", "  ", "courseId", 1, "note", NOTE), "email", "required"),
                Arguments.of("no course", fields("email", email, "courseId", null, "note", NOTE), "courseId",
                        "required"));
    }

    @Test
    void endsAManualEnrollmentWithANoteAndThePaidLessonsCloseAgain() {
        String slug = newSlug();
        long course = courses.onSale(slug);
        long lesson = paidLessonOf(course);
        String email = StudentApi.newEmail();
        String token = new StudentApi(bff).signedUp(email, PASSWORD);
        long id = enrollments.granted(email, course);
        assertThat(playback(lesson, token)).hasStatusOk();
        String startedAt = clock.instant().truncatedTo(ChronoUnit.MICROS).toString();
        clock.set(clock.instant().plusSeconds(90));

        MvcTestResult end = enrollments.end(id, "  Cortesia encerrada.  ");

        assertThat(end).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON).bodyJson().isStrictlyEqualTo("""
                {
                  "id": %d,
                  "status": "ENDED",
                  "student": {"id": %d, "email": "%s", "name": "Bia"},
                  "course": {"id": %d, "slug": "%s", "title": "Backend com Node.js", "status": "ON_SALE"},
                  "startedAt": "%s",
                  "origin": "MANUAL",
                  "grantedBy": {"id": %d, "email": "%s", "name": "Ana"},
                  "grantNote": "Cortesia para quem revisou o curso.",
                  "endedAt": "%s",
                  "endReason": "MANUAL",
                  "endedBy": {"id": %d, "email": "%s", "name": "Ana"},
                  "endNote": "Cortesia encerrada."
                }""".formatted(id, studentIdOf(email), email, course, slug, startedAt, admin.id(), admin.email(),
                clock.instant().truncatedTo(ChronoUnit.MICROS), admin.id(), admin.email()));
        assertProblem(playback(lesson, token), "/v1/lessons/%d/playback".formatted(lesson), HttpStatus.CONFLICT,
                "enrollment-required", "Enrollment required", "This needs an active Enrollment in the Course.");
    }

    /** The ending is final: ending again changes nothing, and the only status the endpoint takes is ENDED. */
    @Test
    void keepsAnEndedEnrollmentEndedAsItWasEnded() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String email = StudentApi.newEmail();
        String token = new StudentApi(bff).signedUp(email, PASSWORD);
        long id = enrollments.granted(email, course);
        String ended = body(enrollments.end(id, "Primeiro encerramento."));
        clock.set(clock.instant().plusSeconds(90));

        MvcTestResult endedAgain = enrollments.end(id, "Segundo encerramento.");
        MvcTestResult reactivated = enrollments.changeStatus(Long.toString(id), "ACTIVE", "Reativar.");

        assertThat(endedAgain).hasStatusOk().bodyJson().isStrictlyEqualTo(ended);
        assertThat(reactivated).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().isLenientlyEqualTo("""
                {"type": "https://aulaflix.com.br/problems/invalid-request",
                 "errors": [{"field": "status", "code": "invalid-format"}]}""");
        assertThat(enrollments.get(Long.toString(id))).hasStatusOk().bodyJson().isStrictlyEqualTo(ended);
        assertThat(playback(lesson, token)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void grantsANewEnrollmentAfterTheEndAndKeepsTheEndedOne() {
        long course = courses.onSale(newSlug());
        long lesson = paidLessonOf(course);
        String email = StudentApi.newEmail();
        String token = new StudentApi(bff).signedUp(email, PASSWORD);
        long first = enrollments.granted(email, course);
        enrollments.ended(first);

        MvcTestResult again = enrollments.grant(email, course, NOTE);

        assertThat(again).hasStatus(HttpStatus.CREATED).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        assertThat(((Number) JsonPath.read(body(again), "$.id")).longValue()).isNotEqualTo(first);
        assertThat(playback(lesson, token)).hasStatusOk();
        assertThat(enrollments.get(Long.toString(first))).bodyJson().extractingPath("$.status").isEqualTo("ENDED");
    }

    @Test
    void readsAnEnrollmentAtTheAddressItsGrantGave() {
        long course = courses.onSale(newSlug());
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);
        MvcTestResult grant = enrollments.grant(email, course, NOTE);

        MvcTestResult read = enrollments.get(grant.getResponse().getHeader(HttpHeaders.LOCATION)
                .substring("/v1/admin/enrollments/".length()));

        assertThat(read).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON).bodyJson()
                .isStrictlyEqualTo(body(grant));
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999999999", "0", "-1", "007", "1.5", "abc", "99999999999999999999"})
    void answersAnIdOfAnyShapeLikeAnUnknownEnrollment(String id) {
        assertEnrollmentNotFound(enrollments.get(id), "/v1/admin/enrollments/" + id);
        assertEnrollmentNotFound(enrollments.changeStatus(id, "ENDED", NOTE),
                "/v1/admin/enrollments/%s/status".formatted(id));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidStatusChanges")
    void refusesAnInvalidStatusChangeWithItsCode(String description, String status, String note, String field,
                                                 String code) {
        long course = courses.onSale(newSlug());
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);
        long id = enrollments.granted(email, course);

        assertThat(enrollments.changeStatus(Long.toString(id), status, note)).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "status": 400,
                          "instance": "/v1/admin/enrollments/%d/status",
                          "errors": [{"field": "%s", "code": "%s"}]
                        }""".formatted(id, field, code));
        assertThat(enrollments.get(Long.toString(id))).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
    }

    static Stream<Arguments> invalidStatusChanges() {
        return Stream.of(
                Arguments.of("no note", "ENDED", null, "note", "required"),
                Arguments.of("blank note", "ENDED", "   ", "note", "required"),
                Arguments.of("note of 501 characters", "ENDED", "a".repeat(501), "note", "too-long"),
                Arguments.of("no status", null, NOTE, "status", "required"),
                Arguments.of("unknown status", "PAUSED", NOTE, "status", "invalid-format"),
                Arguments.of("status other than ENDED", "ACTIVE", NOTE, "status", "invalid-format"));
    }

    @Test
    void listsACoursesEnrollmentsNewestFirstEachAsItsOwnReadShowsIt() {
        long course = courses.onSale(newSlug());
        String first = enrolledStudentIn(course);
        clock.set(clock.instant().plusSeconds(60));
        String second = enrolledStudentIn(course);
        clock.set(clock.instant().plusSeconds(60));
        long ended = enrollments.granted(signedUpStudent(), course);
        enrollments.ended(ended);

        MvcTestResult list = enrollments.list("courseId=" + course);

        assertThat(list).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON).bodyJson().isStrictlyEqualTo("""
                {
                  "items": [%s, %s, %s],
                  "page": 0,
                  "size": 20,
                  "totalItems": 3,
                  "totalPages": 1
                }""".formatted(body(enrollments.get(Long.toString(ended))), readOf(second), readOf(first)));
    }

    @Test
    void filtersByTheStudentsEmailTrimmedAndLowerCased() {
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);
        long onlyOne = enrollments.granted(email, courses.onSale(newSlug()));
        enrolledStudentIn(courses.onSale(newSlug()));

        MvcTestResult list = enrollments.list("email= " + email.toUpperCase(Locale.ROOT) + " ");

        assertThat(list).hasStatusOk().bodyJson().extractingPath("$.items[*].id").asArray()
                .containsExactly((int) onlyOne);
    }

    @Test
    void filtersByWhetherTheEnrollmentIsActive() {
        long course = courses.onSale(newSlug());
        long active = enrollments.granted(signedUpStudent(), course);
        long ended = enrollments.granted(signedUpStudent(), course);
        enrollments.ended(ended);

        assertThat(enrollments.list("courseId=%d&active=true".formatted(course))).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].id").asArray().containsExactly((int) active);
        assertThat(enrollments.list("courseId=%d&active=false".formatted(course))).hasStatusOk().bodyJson()
                .extractingPath("$.items[*].id").asArray().containsExactly((int) ended);
    }

    @Test
    void combinesTheFilters() {
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);
        long course = courses.onSale(newSlug());
        long ended = enrollments.granted(email, course);
        enrollments.ended(ended);
        long active = enrollments.granted(email, course);
        enrollments.granted(email, courses.onSale(newSlug()));
        enrollments.granted(signedUpStudent(), course);

        assertThat(enrollments.list("email=%s&courseId=%d&active=true".formatted(email, course))).hasStatusOk()
                .bodyJson().extractingPath("$.items[*].id").asArray().containsExactly((int) active);
    }

    @Test
    void pagesThroughTheList() {
        long course = courses.onSale(newSlug());
        String oldest = enrolledStudentIn(course);
        clock.set(clock.instant().plusSeconds(60));
        enrolledStudentIn(course);
        clock.set(clock.instant().plusSeconds(60));
        enrolledStudentIn(course);

        MvcTestResult lastPage = enrollments.list("courseId=%d&size=2&page=1".formatted(course));

        assertThat(lastPage).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"items": [%s], "page": 1, "size": 2, "totalItems": 3, "totalPages": 2}""".formatted(readOf(oldest)));
    }

    @Test
    void capsThePageAtAHundredEnrollments() {
        assertThat(enrollments.list("courseId=999999999999&size=1000")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"items": [], "page": 0, "size": 100, "totalItems": 0, "totalPages": 0}""");
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999999999", "0", "-1", "007", "1.5", "abc", "99999999999999999999"})
    void answersACourseIdOfAnyShapeLikeAnUnknownCourse(String courseId) {
        enrolledStudentIn(courses.onSale(newSlug()));

        assertThat(enrollments.list("courseId=" + courseId)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                {"items": [], "page": 0, "size": 20, "totalItems": 0, "totalPages": 0}""");
    }

    @Test
    void refusesAnActiveFilterThatIsNeitherTrueNorFalse() {
        assertThat(enrollments.list("active=yes")).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "status": 400,
                          "instance": "/v1/admin/enrollments",
                          "errors": [{"field": "active", "code": "invalid-format"}]
                        }""");
    }

    /** A filter the Admin believes they applied is refused, never ignored; the order is fixed, newest first. */
    @ParameterizedTest
    @ValueSource(strings = {"status=ACTIVE", "sort=startedAt,asc", "courseId=1&student=bia"})
    void refusesAnyOtherQueryParameter(String query) {
        assertProblem(enrollments.list(query), "/v1/admin/enrollments", HttpStatus.BAD_REQUEST, "invalid-request",
                "Invalid request",
                "This endpoint takes only these query parameters: email, courseId, active, page, size.");
    }

    /** A Student must never grant themselves a Course, even with the BFF's key. */
    @Test
    void forbidsAStudentAndAsksForASessionWithoutOne() {
        long course = courses.onSale(newSlug());
        String email = StudentApi.newEmail();
        String studentToken = new StudentApi(bff).signedUp(email, PASSWORD);
        String grant = JsonPath.parse(fields("email", email, "courseId", course, "note", NOTE)).jsonString();

        MvcTestResult asAStudent = bff.post("/v1/admin/enrollments")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON).content(grant).exchange();
        MvcTestResult withoutASession = mvc.post().uri("/v1/admin/enrollments")
                .contentType(MediaType.APPLICATION_JSON).content(grant).exchange();

        assertProblem(asAStudent, "/v1/admin/enrollments", HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "This session's role may not do this.");
        assertThat(withoutASession).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(enrollments.list("email=" + email)).bodyJson().extractingPath("$.totalItems").isEqualTo(0);
        assertThat(bff.get("/v1/admin/enrollments").header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .exchange()).hasStatus(HttpStatus.FORBIDDEN);
    }

    /** Nor may a Student end someone else's Enrollment, or read one. */
    @Test
    void forbidsAStudentToReadOrEndAnEnrollment() {
        long course = courses.onSale(newSlug());
        String email = signedUpStudent();
        long id = enrollments.granted(email, course);
        String studentToken = new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD);

        assertThat(bff.put("/v1/admin/enrollments/%d/status".formatted(id))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"ENDED\", \"note\": \"x\"}")
                .exchange()).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(bff.get("/v1/admin/enrollments/" + id).header(HttpHeaders.AUTHORIZATION, "Bearer " + studentToken)
                .exchange()).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(enrollments.get(Long.toString(id))).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
    }

    private String signedUpStudent() {
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);
        return email;
    }

    /** The email of a new Student granted an Enrollment in the Course. */
    private String enrolledStudentIn(long course) {
        String email = signedUpStudent();
        enrollments.granted(email, course);
        return email;
    }

    /** The single read of the Student's only Enrollment. */
    private String readOf(String email) {
        MvcTestResult list = enrollments.list("email=" + email);
        assertThat(list).bodyJson().extractingPath("$.items").asArray().hasSize(1);
        return body(enrollments.get(JsonPath.read(body(list), "$.items[0].id").toString()));
    }

    private long paidLessonOf(long course) {
        return courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"), "Rotas no Express",
                "rotas-no-express", "five-seconds.mp4");
    }

    private long studentIdOf(String email) {
        return new StoredAccounts(jdbc).find(email).orElseThrow().id();
    }

    private MvcTestResult playback(long lesson, String token) {
        return bff.get("/v1/lessons/%d/playback".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
    }

    private void assertStudentAccountRequired(MvcTestResult result) {
        assertProblem(result, "/v1/admin/enrollments", HttpStatus.CONFLICT, "student-account-required",
                "Student Account required", "No Student Account has this email: the person signs up first.");
    }

    private void assertEnrollmentNotFound(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.NOT_FOUND, "enrollment-not-found", "Enrollment not found",
                "No Enrollment has this id.");
    }

    /** Every shape a body's id may come in but a sequence's: none is a Course, or a Lesson, so none answers 400. */
    static Stream<Object> idsOfAnyShape() {
        return Stream.of("abc", "", "007", 1.5, 0, -1, new BigInteger("99999999999999999999"));
    }

    private void assertCourseNotEnrollable(MvcTestResult result) {
        assertProblem(result, "/v1/admin/enrollments", HttpStatus.CONFLICT, "course-not-enrollable",
                "Course not enrollable", "Only a Coming soon or On sale Course takes Enrollments.");
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
}
