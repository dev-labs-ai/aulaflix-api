package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.StudentOrders;

/**
 * Placing Orders has hard limits, so that scripts can't drain the Asaas quota: 10 an hour per Student, 30 an hour per
 * client IP and 500 an hour for everyone. Every placement counts, whatever it answers; these ask for a Course that
 * does not exist, which Asaas never hears of.
 */
class CheckoutLimitTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final long NO_SUCH_COURSE = Long.MAX_VALUE;
    private static final int PER_STUDENT = 10;
    private static final int PER_IP = 30;
    private static final int FOR_EVERYONE = 500;

    @Test
    void refusesTheStudents11thPlacementWithinAnHour() {
        Instant start = clock.instant();
        BffApi bff = new BffApi(mvc);
        StudentOrders student = newStudent(bff);
        assertThat(placements(student, PER_STUDENT)).containsOnly(HttpStatus.CONFLICT.value());

        clock.set(start.plus(Duration.ofHours(1)).minusSeconds(1));
        assertRateLimited(student.placePix(NO_SUCH_COURSE, null), "1");
        assertThat(newStudent(bff).placePix(NO_SUCH_COURSE, null)).hasStatus(HttpStatus.CONFLICT);
        clock.set(start.plus(Duration.ofHours(1)));
        assertThat(student.placePix(NO_SUCH_COURSE, null)).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void refusesTheIps31stPlacementWithinAnHourWhicheverStudentMakesIt() {
        BffApi bff = new BffApi(mvc);
        for (int i = 0; i < PER_IP / PER_STUDENT; i++) {
            assertThat(placements(newStudent(bff), PER_STUDENT)).containsOnly(HttpStatus.CONFLICT.value());
        }

        StudentOrders another = newStudent(bff);
        assertRateLimited(another.placePix(NO_SUCH_COURSE, null), "3600");
        assertThat(another.list()).hasStatusOk();
    }

    /**
     * Other tests have placed Orders this hour, and some at a later clock, so the refusal comes at the 500th placement
     * at the latest, and the window ends when its Retry-After says. Moving the clock there opens a new window, which
     * the rest of the suite then shares.
     */
    @Test
    void refusesEveryonesPlacementsPastFiveHundredAnHour() {
        int placed = 0;
        MvcTestResult refused = null;
        while (refused == null && placed <= FOR_EVERYONE) {
            StudentOrders student = newStudent(new BffApi(mvc));
            for (int i = 0; i < PER_STUDENT && refused == null; i++) {
                MvcTestResult placement = student.placePix(NO_SUCH_COURSE, null);
                if (placement.getResponse().getStatus() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                    refused = placement;
                } else {
                    assertThat(placement).hasStatus(HttpStatus.CONFLICT);
                    placed++;
                }
            }
        }

        assertThat(placed).isPositive().isLessThanOrEqualTo(FOR_EVERYONE);
        assertThat(refused).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        StudentOrders elsewhere = newStudent(new BffApi(mvc));
        assertThat(elsewhere.placePix(NO_SUCH_COURSE, null)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        Duration wait = Duration.ofSeconds(Long.parseLong(refused.getResponse().getHeader(HttpHeaders.RETRY_AFTER)));
        assertThat(wait).isPositive().isLessThanOrEqualTo(Duration.ofHours(2));
        clock.set(clock.instant().plus(wait));
        assertThat(elsewhere.placePix(NO_SUCH_COURSE, null)).hasStatus(HttpStatus.CONFLICT);
    }

    private StudentOrders newStudent(BffApi bff) {
        return new StudentOrders(bff, new StudentApi(bff).signedUp(StudentApi.newEmail(), PASSWORD));
    }

    private static List<Integer> placements(StudentOrders student, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> student.placePix(NO_SUCH_COURSE, null).getResponse().getStatus())
                .toList();
    }

    private void assertRateLimited(MvcTestResult result, String retryAfter) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, retryAfter)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/rate-limited",
                          "title": "Rate limited",
                          "status": 429,
                          "detail": "Too many requests. Try again in Retry-After seconds.",
                          "instance": "/v1/account/orders",
                          "timestamp": "%s"
                        }""".formatted(clock.instant()));
    }
}
