package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.Mailpit;
import com.devlabs.aulaflix.StoredCourses;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.WaitlistApi;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;

/**
 * Logs must not become a leak. This asserts only what never appears, whatever the wording of the lines: the emails
 * that joined and left a Waitlist, through refusals, the soft limit and the Admin's reads included, and the emails and
 * unsubscribe tokens a launch handles.
 */
@ExtendWith(OutputCaptureExtension.class)
class WaitlistLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Test
    void logsNoEmailThatJoinsOrLeaves(CapturedOutput output) {
        String adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        String admin = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        long course = new StoredCourses(jdbc).insertComingSoon(AdminCourses.newSlug(), clock.instant());
        long draft = new AdminCourses(mvc, admin).draft(AdminCourses.newSlug());
        BffApi bff = new BffApi(mvc);
        WaitlistApi waitlists = new WaitlistApi(bff);
        String visitor = newEmail();
        String invalid = "not-an-email-" + UUID.randomUUID();
        String studentEmail = newEmail();
        String student = new StudentApi(bff).signedUp(studentEmail, PASSWORD);

        assertThat(waitlists.join(course, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.join(draft, visitor)).hasStatus(HttpStatus.CONFLICT);
        assertThat(waitlists.join(course, invalid)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(waitlists.join(course, visitor)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(waitlists.check(student, course)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(waitlists.enter(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(student, draft)).hasStatus(HttpStatus.CONFLICT);
        assertThat(waitlists.leave(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri("/v1/admin/courses/" + course).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .exchange()).hasStatusOk();

        assertThat(output.getAll()).isNotBlank()
                .contains("waitlist-closed", "not-on-waitlist")
                .doesNotContainIgnoringCase(visitor)
                .doesNotContainIgnoringCase(invalid)
                .doesNotContainIgnoringCase(studentEmail);
    }

    @Test
    void logsNoEmailNorTokenThatTheLaunchOrAnUnsubscribeHandles(CapturedOutput output) {
        String adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(adminEmail, "Ana", PASSWORD);
        AdminCourses courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(adminEmail, PASSWORD),
                storedVideos);
        String slug = AdminCourses.newSlug();
        long course = courses.announced(slug);
        long other = courses.announced(AdminCourses.newSlug());
        WaitlistApi waitlists = new WaitlistApi(new BffApi(mvc));
        String visitor = newEmail();
        assertThat(waitlists.join(course, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.join(other, visitor)).hasStatus(HttpStatus.NO_CONTENT);

        courses.launch(course, slug);
        outbox.drain();
        String token = WaitlistApi.unsubscribeTokenIn(mailpit.to(visitor).getFirst());
        String tampered = new StringBuilder(token).reverse().toString();
        assertThat(waitlists.unsubscribe(tampered)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(waitlists.unsubscribe(token)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(output.getAll()).isNotBlank()
                .contains("invalid-unsubscribe-link")
                .doesNotContainIgnoringCase(visitor)
                .doesNotContain(token)
                .doesNotContain(tampered);
    }
}
