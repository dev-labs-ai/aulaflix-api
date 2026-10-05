package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.WaitlistApi;
import com.devlabs.aulaflix.service.AccountService;
import com.devlabs.aulaflix.service.EmailOutbox;
import com.jayway.jsonpath.JsonPath;

/**
 * A Course's launch, Coming soon to On sale, emails its Waitlist once, in the transaction of the move: one launch email
 * per entry, but none to a Student enrolled in the Course already. The entries go, and the Admin's reads show how many
 * were notified. Every address is the test's own, so a test reads only its own emails.
 */
class WaitlistLaunchTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String LAUNCH_SUBJECT = "Lançamento: Backend com Node.js já está à venda";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Autowired
    private EmailOutbox outbox;

    @Autowired
    private Mailpit mailpit;

    @Autowired
    private JdbcTemplate jdbc;

    private AdminCourses courses;

    private AdminEnrollments enrollments;

    private BffApi bff;

    private WaitlistApi waitlists;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        String adminToken = new AdminApi(mvc).sessionToken(email, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        enrollments = new AdminEnrollments(mvc, adminToken);
        // Some tests join more Waitlists from one IP than the soft limit lets in without a CAPTCHA
        bff = new BffApi(mvc).solvingCaptchas();
        waitlists = new WaitlistApi(bff);
    }

    @Test
    void emailsEveryEntryButTheStudentEnrolledAlreadyAndRemovesTheEntries() {
        String slug = newSlug();
        long course = courses.announced(slug);
        String visitor = StudentApi.newEmail();
        String studentEmail = StudentApi.newEmail();
        String enrolledEmail = StudentApi.newEmail();
        String student = new StudentApi(bff).signedUp(studentEmail, PASSWORD);
        String enrolled = new StudentApi(bff).signedUp(enrolledEmail, PASSWORD);
        assertThat(waitlists.join(course, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(enrolled, course)).hasStatus(HttpStatus.NO_CONTENT);
        enrollments.granted(enrolledEmail, course);

        courses.readyToLaunch(course, slug);
        assertThat(courses.changeStatus(course, "ON_SALE")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", status -> assertThat(status).isEqualTo("ON_SALE"))
                .hasPathSatisfying("$.notifiedCount", count -> assertThat(count).isEqualTo(2))
                .doesNotHavePath("$.waitlistCount");
        outbox.drain();

        assertThat(launchEmailsTo(visitor)).hasSize(1);
        assertThat(launchEmailsTo(studentEmail)).hasSize(1);
        assertThat(launchEmailsTo(enrolledEmail)).isEmpty();
        assertThat(courses.get(course)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.notifiedCount", count -> assertThat(count).isEqualTo(2));
        assertThat(waitlists.check(student, course)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(waitlists.check(enrolled, course)).hasStatus(HttpStatus.NOT_FOUND);
        String visitorSignedUp = new StudentApi(bff).signedUp(visitor, PASSWORD);
        assertThat(waitlists.check(visitorSignedUp, course)).hasStatus(HttpStatus.NOT_FOUND);
    }

    /** Only an active Enrollment spares the email: a Student whose Enrollment ended has to buy again. */
    @Test
    void emailsAStudentWhoseEnrollmentEnded() {
        String slug = newSlug();
        long course = courses.announced(slug);
        String formerEmail = StudentApi.newEmail();
        String former = new StudentApi(bff).signedUp(formerEmail, PASSWORD);
        assertThat(waitlists.enter(former, course)).hasStatus(HttpStatus.NO_CONTENT);
        enrollments.ended(enrollments.granted(formerEmail, course));

        courses.launch(course, slug);
        outbox.drain();

        assertThat(launchEmailsTo(formerEmail)).hasSize(1);
        assertThat(courses.get(course)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.notifiedCount", count -> assertThat(count).isEqualTo(1));
    }

    @Test
    void sendsNothingMoreWhenTheLaunchIsRetried() {
        String slug = newSlug();
        long course = courses.announced(slug);
        String first = StudentApi.newEmail();
        String second = StudentApi.newEmail();
        assertThat(waitlists.join(course, first)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.join(course, second)).hasStatus(HttpStatus.NO_CONTENT);
        courses.launch(course, slug);
        outbox.drain();

        assertThat(courses.changeStatus(course, "ON_SALE")).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.notifiedCount", count -> assertThat(count).isEqualTo(2));
        outbox.drain();

        assertThat(launchEmailsTo(first)).hasSize(1);
        assertThat(launchEmailsTo(second)).hasSize(1);
        assertThat(courses.get(course)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.notifiedCount", count -> assertThat(count).isEqualTo(2));
    }

    @Test
    void sendsNothingWhenADraftGoesOnSale() {
        long course = courses.onSale(newSlug());

        assertThat(courses.get(course)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", status -> assertThat(status).isEqualTo("ON_SALE"))
                .doesNotHavePath("$.notifiedCount");
    }

    @Test
    void countsNoOneNotifiedWhenTheWaitlistIsEmpty() {
        long course = courses.launchedAfterAnnouncement(newSlug());

        assertThat(courses.get(course)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.notifiedCount", count -> assertThat(count).isEqualTo(0));
    }

    @Test
    void emailsTheSummaryThePricesAndTheCoursesLinkWithOneClickUnsubscribe() {
        String slug = newSlug();
        long course = courses.announced(slug);
        String visitor = StudentApi.newEmail();
        assertThat(waitlists.join(course, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        courses.launch(course, slug);
        outbox.drain();

        assertThat(launchEmailsTo(visitor)).singleElement().satisfies(email -> {
            String token = WaitlistApi.unsubscribeTokenIn(email);
            assertThat(email.from()).isEqualTo("AulaFlix <contato@aulaflix.com.br>");
            assertThat(email.to()).containsExactly(visitor);
            assertThat(email.text().replace("\r\n", "\n")).isEqualTo("""
                    Olá!

                    O curso Backend com Node.js, que você estava esperando, acaba de ser lançado na AulaFlix.

                    Construa APIs REST com Node.js e TypeScript.

                    Sai por R$ 497,00 no cartão, em até 10x de R$ 49,70 sem juros, ou R$ 447,30 no Pix.

                    Para conhecer o curso e comprar, abra:

                    http://localhost:3001/cursos/%s

                    Você recebeu este email porque entrou na lista de espera deste curso. Para sair de todas as listas \
                    de espera da AulaFlix, abra:

                    http://localhost:3001/cancelar-aviso#%s

                    Equipe AulaFlix
                    """.formatted(slug, token));
            assertThat(email.headers())
                    .containsEntry("List-Unsubscribe",
                            List.of("<http://localhost:3001/api/waitlist/unsubscribe?token=" + token + ">"))
                    .containsEntry("List-Unsubscribe-Post", List.of("List-Unsubscribe=One-Click"));
        });
    }

    @Test
    void sendsExactlyOneEmailPerEntryWhenTwoLaunchesRace() throws Exception {
        String slug = newSlug();
        long course = courses.announced(slug);
        String first = StudentApi.newEmail();
        String second = StudentApi.newEmail();
        assertThat(waitlists.join(course, first)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.join(course, second)).hasStatus(HttpStatus.NO_CONTENT);
        courses.readyToLaunch(course, slug);

        CyclicBarrier start = new CyclicBarrier(2);
        try (ExecutorService admins = Executors.newFixedThreadPool(2)) {
            List<Future<MvcTestResult>> launches = admins.invokeAll(List.of(
                    () -> launchAfter(start, course), () -> launchAfter(start, course)));
            for (Future<MvcTestResult> launch : launches) {
                assertThat(launch.get()).hasStatusOk().bodyJson()
                        .hasPathSatisfying("$.notifiedCount", count -> assertThat(count).isEqualTo(2));
            }
        }
        outbox.drain();

        assertThat(launchEmailsTo(first)).hasSize(1);
        assertThat(launchEmailsTo(second)).hasSize(1);
    }

    /**
     * The launch fails as it commits, after every email is queued and every entry deleted: a constraint trigger of the
     * test's own, deferred to the commit, refuses this one Course's move.
     */
    @Test
    void keepsTheEntriesAndQueuesNothingWhenTheLaunchFails() {
        String slug = newSlug();
        long course = courses.announced(slug);
        String visitor = StudentApi.newEmail();
        String studentEmail = StudentApi.newEmail();
        String student = new StudentApi(bff).signedUp(studentEmail, PASSWORD);
        assertThat(waitlists.join(course, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.enter(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        courses.readyToLaunch(course, slug);

        try (FailingCommit failing = new FailingCommit(jdbc, course)) {
            assertThat(courses.changeStatus(course, "ON_SALE")).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        outbox.drain();

        assertThat(courses.get(course)).hasStatusOk().bodyJson()
                .hasPathSatisfying("$.status", status -> assertThat(status).isEqualTo("COMING_SOON"))
                .hasPathSatisfying("$.waitlistCount", count -> assertThat(count).isEqualTo(2))
                .doesNotHavePath("$.notifiedCount");
        assertThat(waitlists.check(student, course)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(launchEmailsTo(visitor)).isEmpty();
        assertThat(launchEmailsTo(studentEmail)).isEmpty();

        courses.moveTo(course, "ON_SALE");
        outbox.drain();

        assertThat(launchEmailsTo(visitor)).hasSize(1);
        assertThat(launchEmailsTo(studentEmail)).hasSize(1);
    }

    @Test
    void takesTheEmailOffEveryWaitlistWithTheTokenOfItsLaunchEmail() {
        String slug = newSlug();
        long launched = courses.announced(slug);
        long other = courses.announced(newSlug());
        long another = courses.announced(newSlug());
        String visitor = StudentApi.newEmail();
        String someoneElse = StudentApi.newEmail();
        for (long course : List.of(launched, other, another)) {
            assertThat(waitlists.join(course, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        }
        assertThat(waitlists.join(other, someoneElse)).hasStatus(HttpStatus.NO_CONTENT);
        courses.launch(launched, slug);
        outbox.drain();
        String token = WaitlistApi.unsubscribeTokenIn(launchEmailsTo(visitor).getFirst());

        assertThat(waitlists.unsubscribe(token)).hasStatus(HttpStatus.NO_CONTENT).hasBodyTextEqualTo("");

        assertThat(waitlistCount(other)).isEqualTo(1);
        assertThat(waitlistCount(another)).isZero();
        assertThat(waitlists.unsubscribe(token)).hasStatus(HttpStatus.NO_CONTENT);
    }

    /** Joining again after unsubscribing is fresh consent, which the next launch honours. */
    @Test
    void letsTheEmailJoinAgainAfterUnsubscribing() {
        String slug = newSlug();
        long launched = courses.announced(slug);
        long later = courses.announced(newSlug());
        String visitor = StudentApi.newEmail();
        assertThat(waitlists.join(launched, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        courses.launch(launched, slug);
        outbox.drain();
        assertThat(waitlists.unsubscribe(WaitlistApi.unsubscribeTokenIn(launchEmailsTo(visitor).getFirst())))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(waitlists.join(later, visitor)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(waitlistCount(later)).isOne();
    }

    @Test
    void refusesATamperedToken() {
        String slug = newSlug();
        long launched = courses.announced(slug);
        long other = courses.announced(newSlug());
        String visitor = StudentApi.newEmail();
        assertThat(waitlists.join(launched, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(waitlists.join(other, visitor)).hasStatus(HttpStatus.NO_CONTENT);
        courses.launch(launched, slug);
        outbox.drain();
        String token = WaitlistApi.unsubscribeTokenIn(launchEmailsTo(visitor).getFirst());
        String tampered = token.substring(0, 20) + (token.charAt(20) == 'A' ? 'B' : 'A') + token.substring(21);

        assertInvalidUnsubscribeLink(waitlists.unsubscribe(tampered));
        assertInvalidUnsubscribeLink(waitlists.unsubscribe("not-a-token"));

        assertThat(waitlistCount(other)).isOne();
    }

    @Test
    void asksForTheToken() {
        assertThat(waitlists.unsubscribeWith("{}")).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isLenientlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "status": 400,
                          "errors": [{"field": "token", "code": "required"}]
                        }""");
    }

    private void assertInvalidUnsubscribeLink(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-unsubscribe-link",
                          "title": "Invalid unsubscribe link",
                          "status": 400,
                          "detail": "The link is not one AulaFlix sent, or it was changed: open it again from the email.",
                          "instance": "/v1/waitlist-unsubscriptions",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }

    private MvcTestResult launchAfter(CyclicBarrier start, long course) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return courses.changeStatus(course, "ON_SALE");
    }

    private int waitlistCount(long course) {
        MvcTestResult read = courses.get(course);
        assertThat(read).hasStatusOk();
        return JsonPath.read(AdminApi.body(read), "$.waitlistCount");
    }

    private List<Mailpit.Email> launchEmailsTo(String address) {
        return mailpit.to(address).stream().filter(email -> email.subject().equals(LAUNCH_SUBJECT)).toList();
    }

    /**
     * Fails the commit of any transaction that updates the Course, until closed: a constraint trigger runs when the
     * transaction commits, so everything the transaction did runs first.
     */
    private static final class FailingCommit implements AutoCloseable {

        private final JdbcTemplate jdbc;
        private final String trigger;

        FailingCommit(JdbcTemplate jdbc, long courseId) {
            this.jdbc = jdbc;
            this.trigger = "fail_commit_of_course_" + courseId;
            jdbc.execute("""
                    create or replace function fail_the_commit() returns trigger language plpgsql as $$
                    begin
                        raise exception 'The test fails this commit';
                    end $$""");
            jdbc.execute("""
                    create constraint trigger %s after update on courses deferrable initially deferred
                    for each row when (new.id = %d) execute function fail_the_commit()""".formatted(trigger,
                    courseId));
        }

        @Override
        public void close() {
            jdbc.execute("drop trigger %s on courses".formatted(trigger));
        }
    }
}
