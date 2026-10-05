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
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.WaitlistApi;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.jayway.jsonpath.JsonPath;

/**
 * A Coming soon Course's Waitlist, as the BFF calls it: a Visitor joins by email, a Student in one click with the
 * Account's email, and only a Student leaves. Joining never tells whether anyone is listed, nor sends an email.
 */
class WaitlistControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final long UNKNOWN_COURSE = 999_999_999_999L;

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    private String adminToken;

    private AdminCourses courses;

    private BffApi bff;

    private WaitlistApi waitlists;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        adminToken = new AdminApi(mvc).sessionToken(email, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        bff = new BffApi(mvc);
        waitlists = new WaitlistApi(bff);
    }

    @Test
    void takesAVisitorsEmailOnceNormalizedHoweverOftenTheyJoin() {
        long course = courses.announced(newSlug());
        String email = StudentApi.newEmail();

        assertThat(waitlists.join(course, "  " + email.toUpperCase() + " ")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.join(course, email)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(waitlistCount(course)).isEqualTo(1);
    }

    @Test
    void answersAJoinAlikeForAnEmailWithAnAccount() {
        long course = courses.announced(newSlug());
        String email = StudentApi.newEmail();
        new StudentApi(bff).signedUp(email, PASSWORD);

        assertThat(waitlists.join(course, email)).hasStatus(HttpStatus.NO_CONTENT).hasBodyTextEqualTo("");
        assertThat(waitlistCount(course)).isEqualTo(1);
    }

    @Test
    void sendsNoEmailOnJoining() {
        long course = courses.announced(newSlug());
        String visitor = StudentApi.newEmail();
        String studentEmail = StudentApi.newEmail();
        String student = new StudentApi(bff).signedUp(studentEmail, PASSWORD);
        outbox.drain();

        assertThat(waitlists.join(course, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        outbox.drain();

        assertThat(mailpit.to(visitor)).isEmpty();
        assertThat(mailpit.to(studentEmail)).singleElement().extracting(Mailpit.Email::subject)
                .isEqualTo("Boas-vindas à AulaFlix: confirme seu email");
    }

    @Test
    void closesTheWaitlistOfADraftAnOnSaleCourseAndAnUnknownOneAlike() {
        long draft = courses.completeDraft(newSlug());
        long onSale = courses.onSale(newSlug());
        String email = StudentApi.newEmail();

        assertWaitlistClosed(waitlists.join(draft, email), WaitlistApi.ENTRIES);
        assertWaitlistClosed(waitlists.join(onSale, email), WaitlistApi.ENTRIES);
        assertWaitlistClosed(waitlists.join(UNKNOWN_COURSE, email), WaitlistApi.ENTRIES);
    }

    @Test
    void stopsTakingEntriesOnceTheCourseGoesOnSale() {
        String slug = newSlug();
        long course = courses.announced(slug);
        assertThat(waitlists.join(course, StudentApi.newEmail())).hasStatus(HttpStatus.NO_CONTENT);
        courses.launch(course, slug);

        assertWaitlistClosed(waitlists.join(course, StudentApi.newEmail()), WaitlistApi.ENTRIES);
        assertThat(adminRead(course)).hasStatusOk().bodyJson().doesNotHavePath("$.waitlistCount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"bia", "bia@example", "bia @example.com", "@example.com"})
    void refusesAnInvalidEmail(String email) {
        long course = courses.announced(newSlug());

        assertThat(waitlists.join(course, email)).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "email", "code": "invalid-email"}]
                        }""");
        assertThat(waitlistCount(course)).isZero();
    }

    @Test
    void refusesAnInvalidEmailBeforeLookingAtTheCourse() {
        assertThat(waitlists.join(UNKNOWN_COURSE, "bia")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {"errors": [{"field": "email", "code": "invalid-email"}]}""");
        assertThat(waitlists.join(UNKNOWN_COURSE, "a".repeat(243) + "@example.com")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {"errors": [{"field": "email", "code": "too-long"}]}""");
    }

    @Test
    void refusesAJoinMissingItsFields() {
        assertThat(waitlists.joinWith("{}")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "errors": [{"field": "courseId", "code": "required"}, {"field": "email", "code": "required"}]
                        }""");
    }

    @Test
    void showsAStudentOnAWaitlistTheyJoinedAsAVisitorAndAddsNoSecondEntry() {
        long course = courses.announced(newSlug());
        String email = StudentApi.newEmail();
        assertThat(waitlists.join(course, email.toUpperCase())).hasStatus(HttpStatus.NO_CONTENT);
        String student = new StudentApi(bff).signedUp(email, PASSWORD);

        assertThat(waitlists.check(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(student, course)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(waitlistCount(course)).isEqualTo(1);
    }

    @Test
    void joinsAStudentInOneClickIdempotently() {
        long course = courses.announced(newSlug());
        long other = courses.announced(newSlug());
        String student = new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD);
        assertNotOnWaitlist(waitlists.check(student, course), course);

        assertThat(waitlists.enter(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(student, course)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(waitlists.check(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertNotOnWaitlist(waitlists.check(student, other), other);
        assertThat(waitlistCount(course)).isEqualTo(1);
        assertThat(waitlistCount(other)).isZero();
    }

    @Test
    void closesAStudentsWaitlistOfADraftAnOnSaleCourseAndAnUnknownOneAlike() {
        long draft = courses.completeDraft(newSlug());
        long onSale = courses.onSale(newSlug());
        String student = new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD);

        for (Object course : List.of(draft, onSale, UNKNOWN_COURSE, "abc", "0")) {
            assertWaitlistClosed(waitlists.enter(student, course), WaitlistApi.ofStudent(course));
        }
    }

    @Test
    void tellsAStudentTheyAreOnNoWaitlistOfACourseThatDoesNotExist() {
        String student = new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD);

        for (Object course : List.of(UNKNOWN_COURSE, "abc", "-1")) {
            assertNotOnWaitlist(waitlists.check(student, course), course);
        }
    }

    @Test
    void letsAStudentLeaveIdempotently() {
        long course = courses.announced(newSlug());
        String email = StudentApi.newEmail();
        String student = new StudentApi(bff).signedUp(email, PASSWORD);
        String other = StudentApi.newEmail();
        assertThat(waitlists.join(course, other)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(student, course)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(waitlists.leave(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertNotOnWaitlist(waitlists.check(student, course), course);
        assertThat(waitlists.leave(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.leave(student, "abc")).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.leave(student, UNKNOWN_COURSE)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(waitlistCount(course)).isEqualTo(1);
    }

    @Test
    void forbidsAnAdminEveryEndpoint() {
        long course = courses.announced(newSlug());

        assertForbidden(waitlists.check(adminToken, course), WaitlistApi.ofStudent(course));
        assertForbidden(waitlists.enter(adminToken, course), WaitlistApi.ofStudent(course));
        assertForbidden(waitlists.leave(adminToken, course), WaitlistApi.ofStudent(course));
        assertForbidden(bff.post(WaitlistApi.ENTRIES).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\": %d, \"email\": \"%s\"}".formatted(course, StudentApi.newEmail()))
                .exchange(), WaitlistApi.ENTRIES);
        assertThat(waitlistCount(course)).isZero();
    }

    @Test
    void asksForASessionOnTheStudentsEndpoints() {
        long course = courses.announced(newSlug());
        String path = WaitlistApi.ofStudent(course);

        assertUnauthenticated(bff.get(path).exchange(), path);
        assertUnauthenticated(bff.put(path).exchange(), path);
        assertUnauthenticated(bff.delete(path).exchange(), path);
        assertThat(waitlistCount(course)).isZero();
    }

    @Test
    void countsTheWaitlistOfEachComingSoonCourseInTheAdminsReadsWithoutAnEmail() {
        long draft = courses.completeDraft(newSlug());
        long comingSoon = courses.announced(newSlug());
        long empty = courses.announced(newSlug());
        long onSale = courses.onSale(newSlug());
        String visitor = StudentApi.newEmail();
        String studentEmail = StudentApi.newEmail();
        String student = new StudentApi(bff).signedUp(studentEmail, PASSWORD);
        assertThat(waitlists.join(comingSoon, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(student, comingSoon)).hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult list = mvc.get().uri("/v1/admin/courses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken).exchange();

        assertThat(list).hasStatusOk();
        assertThat(listed(list, comingSoon)).containsEntry("waitlistCount", 2);
        assertThat(listed(list, empty)).containsEntry("waitlistCount", 0);
        assertThat(listed(list, draft)).doesNotContainKey("waitlistCount");
        assertThat(listed(list, onSale)).doesNotContainKey("waitlistCount");
        assertThat(waitlistCount(comingSoon)).isEqualTo(2);
        assertThat(adminRead(draft)).bodyJson().doesNotHavePath("$.waitlistCount");
        assertThat(AdminApi.body(list)).doesNotContain(visitor, studentEmail);
        assertThat(AdminApi.body(adminRead(comingSoon))).doesNotContain(visitor, studentEmail);
    }

    private int waitlistCount(long course) {
        MvcTestResult read = adminRead(course);
        assertThat(read).hasStatusOk();
        return JsonPath.read(AdminApi.body(read), "$.waitlistCount");
    }

    private MvcTestResult adminRead(long course) {
        return mvc.get().uri("/v1/admin/courses/" + course)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                .exchange();
    }

    private static Map<String, Object> listed(MvcTestResult list, long course) {
        List<Map<String, Object>> items =
                JsonPath.read(AdminApi.body(list), "$.items[?(@.id == %d)]".formatted(course));
        assertThat(items).hasSize(1);
        return items.getFirst();
    }

    private void assertWaitlistClosed(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.CONFLICT, "waitlist-closed", "Waitlist closed",
                "Only a Coming soon Course takes Waitlist entries.");
    }

    private void assertNotOnWaitlist(MvcTestResult result, Object course) {
        assertProblem(result, WaitlistApi.ofStudent(course), HttpStatus.NOT_FOUND, "not-on-waitlist",
                "Not on the Waitlist", "No entry of this Course's Waitlist holds the Account's email.");
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
}
