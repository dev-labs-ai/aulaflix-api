package com.devlabs.aulaflix.config;

import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;

/**
 * Playback without a session counts against its client IP: past 30 within an hour, the API refuses the rest of that
 * hour. Every such request counts, whatever it answers; a request with a session never does.
 */
class VisitorPlaybackLimitTest extends IntegrationTest {

    private static final int PLAYS_AN_HOUR = 30;
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    private String adminToken;
    private String freeLesson;
    private String otherLesson;

    @BeforeEach
    void launchACourse() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        adminToken = new AdminApi(mvc).sessionToken(email, PASSWORD);
        AdminCourses courses = new AdminCourses(mvc, adminToken, storedVideos);
        long course = courses.onSale(newSlug());
        freeLesson = playbackPath(courses.freeLessonOf(course));
        otherLesson = playbackPath(courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"),
                "Rotas no Express", "rotas-no-express", "five-seconds.mp4"));
    }

    @Test
    void refusesThe31stPlaybackWithoutASessionFromOneIpWithinAnHour() {
        BffApi bff = new BffApi(mvc);
        play(bff, PLAYS_AN_HOUR);

        assertRateLimited(bff.get(freeLesson).exchange(), freeLesson, "3600");
    }

    @Test
    void countsEveryPlaybackWithoutASessionWhateverItAnswers() {
        BffApi bff = new BffApi(mvc);
        assertThat(statuses(bff, otherLesson, PLAYS_AN_HOUR / 3)).containsOnly(HttpStatus.UNAUTHORIZED.value());
        assertThat(statuses(bff, "/v1/lessons/abc/playback", PLAYS_AN_HOUR / 3))
                .containsOnly(HttpStatus.NOT_FOUND.value());
        play(bff, PLAYS_AN_HOUR / 3);

        assertRateLimited(bff.get(otherLesson).exchange(), otherLesson, "3600");
        assertRateLimited(bff.get(freeLesson).exchange(), freeLesson, "3600");
    }

    @Test
    void servesAnotherIpWhileOneIsRefused() {
        BffApi refused = new BffApi(mvc);
        play(refused, PLAYS_AN_HOUR);
        assertRateLimited(refused.get(freeLesson).exchange(), freeLesson, "3600");

        assertThat(new BffApi(mvc).get(freeLesson)).hasStatusOk();
    }

    @Test
    void servesTheIpAgainOnceTheClockPassesTheHour() {
        Instant start = clock.instant();
        BffApi bff = new BffApi(mvc);
        play(bff, PLAYS_AN_HOUR);

        clock.set(start.plusSeconds(3599));
        assertRateLimited(bff.get(freeLesson).exchange(), freeLesson, "1");
        clock.set(start.plusSeconds(3600));
        play(bff, PLAYS_AN_HOUR);
        assertRateLimited(bff.get(freeLesson).exchange(), freeLesson, "3600");
    }

    @Test
    void neverCountsAPlaybackWithASession() {
        BffApi bff = new BffApi(mvc);
        assertThat(IntStream.rangeClosed(1, PLAYS_AN_HOUR + 1)
                .mapToObj(request -> bff.get(freeLesson).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .exchange().getResponse().getStatus()))
                .containsOnly(HttpStatus.FORBIDDEN.value());

        play(bff, PLAYS_AN_HOUR);
    }

    /** Plays the Free lesson without a session, each time with success. */
    private void play(BffApi bff, int plays) {
        assertThat(statuses(bff, freeLesson, plays))
                .as("the statuses of %d plays from %s", plays, bff.clientIp())
                .containsOnly(HttpStatus.OK.value());
    }

    private static List<Integer> statuses(BffApi bff, String path, int requests) {
        return IntStream.range(0, requests)
                .mapToObj(request -> bff.get(path).exchange().getResponse().getStatus())
                .toList();
    }

    private void assertRateLimited(MvcTestResult result, String path, String retryAfter) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, retryAfter)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/rate-limited",
                          "title": "Rate limited",
                          "status": 429,
                          "detail": "Too many requests. Try again in Retry-After seconds.",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(path, clock.instant()));
    }

    private static String playbackPath(long lesson) {
        return "/v1/lessons/%d/playback".formatted(lesson);
    }
}
