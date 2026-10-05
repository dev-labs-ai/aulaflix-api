package com.devlabs.aulaflix.config;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredCourses;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.Turnstile;
import com.devlabs.aulaflix.WaitlistApi;

/**
 * Joining a Waitlist by email needs no session, so each client IP gets 3 within an hour without a CAPTCHA, and 10
 * within 24 hours whatever they carry and whatever they answer. The Student's one-click join counts against neither.
 */
class WaitlistLimitTest extends IntegrationTest {

    private static final int SOFT = 3;
    private static final int HARD = 10;
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Turnstile turnstile;

    private long course;

    @BeforeEach
    void announceACourse() {
        course = new StoredCourses(jdbc).insertComingSoon(AdminCourses.newSlug(), clock.instant());
    }

    @Test
    void asksForACaptchaFromThe4thJoinByEmailWithinAnHour() {
        BffApi bff = new BffApi(mvc);
        WaitlistApi waitlists = new WaitlistApi(bff);
        assertThat(statuses(SOFT, request -> waitlists.join(course, newEmail())))
                .containsOnly(HttpStatus.NO_CONTENT.value());

        assertCaptchaRequired(waitlists.join(course, newEmail()));
        assertThat(new WaitlistApi(bff.solvingCaptchas()).join(course, newEmail())).hasStatus(HttpStatus.NO_CONTENT);
        String rejected = Turnstile.newToken();
        turnstile.reject(rejected);
        assertCaptchaRequired(new WaitlistApi(bff.withCaptchaToken(rejected)).join(course, newEmail()));
    }

    @Test
    void servesTheIpWithoutACaptchaOnceTheHourEnds() {
        Instant start = clock.instant();
        BffApi bff = new BffApi(mvc);
        WaitlistApi waitlists = new WaitlistApi(bff);
        assertThat(statuses(SOFT, request -> waitlists.join(course, newEmail())))
                .containsOnly(HttpStatus.NO_CONTENT.value());

        clock.set(start.plus(Duration.ofHours(1)).minusSeconds(1));
        assertCaptchaRequired(waitlists.join(course, newEmail()));
        clock.set(start.plus(Duration.ofHours(1)));
        assertThat(waitlists.join(course, newEmail())).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void refusesThe11thJoinByEmailWithin24HoursSolvedCaptchasAndRefusalsIncluded() {
        BffApi bff = new BffApi(mvc);
        WaitlistApi solving = new WaitlistApi(bff.solvingCaptchas());
        assertThat(statuses(HARD / 2, request -> solving.join(course, newEmail())))
                .containsOnly(HttpStatus.NO_CONTENT.value());
        assertThat(statuses(HARD / 2, request -> solving.join(course, "not an email")))
                .containsOnly(HttpStatus.BAD_REQUEST.value());

        assertRateLimited(solving.join(course, newEmail()));
        assertRateLimited(new WaitlistApi(bff).join(course, newEmail()));
    }

    @Test
    void countsNoOneClickJoinOfAStudent() {
        BffApi bff = new BffApi(mvc);
        String student = new StudentApi(bff).signedUp(newEmail(), PASSWORD);
        WaitlistApi waitlists = new WaitlistApi(bff);
        long[] courses = IntStream.range(0, HARD + 1)
                .mapToLong(any -> new StoredCourses(jdbc).insertComingSoon(AdminCourses.newSlug(), clock.instant()))
                .toArray();

        assertThat(statuses(courses.length, index -> waitlists.enter(student, courses[index])))
                .containsOnly(HttpStatus.NO_CONTENT.value());
        assertThat(waitlists.join(course, newEmail())).hasStatus(HttpStatus.NO_CONTENT);
    }

    private void assertRateLimited(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, Long.toString(Duration.ofDays(1).toSeconds()))
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/rate-limited");
    }

    private void assertCaptchaRequired(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .doesNotContainHeader(HttpHeaders.RETRY_AFTER)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/captcha-required");
    }

    private static List<Integer> statuses(int requests, IntFunction<MvcTestResult> request) {
        return IntStream.range(0, requests)
                .mapToObj(request)
                .map(result -> result.getResponse().getStatus())
                .toList();
    }
}
