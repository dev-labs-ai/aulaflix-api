package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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
import com.devlabs.aulaflix.service.AccountService;

/**
 * Every BFF request counts against its client IP, an IPv6 client by its /64: past 600 within a minute, the API refuses
 * the rest of that minute. The Admin, who comes through the SSH tunnel, is not a BFF request and is never counted. The
 * requests read one Course, which costs the same however many Courses the other tests have made.
 */
class BffRequestLimitTest extends IntegrationTest {

    private static final int REQUESTS_A_MINUTE = 600;
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    private String bearer;
    private long courseId;
    private String coursePath;

    @BeforeEach
    void announceACourse() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        String token = new AdminApi(mvc).sessionToken(email, PASSWORD);
        bearer = "Bearer " + token;
        String slug = AdminCourses.newSlug();
        courseId = new AdminCourses(mvc, token).announced(slug);
        coursePath = "/v1/courses/" + slug;
    }

    @Test
    void refusesThe601stRequestFromOneIpWithinAMinute() {
        BffApi bff = new BffApi(mvc);
        serve(bff, REQUESTS_A_MINUTE);

        assertRateLimited(bff.get(coursePath).exchange(), "60");
    }

    @Test
    void servesAnotherIpWhileOneIsRefused() {
        BffApi refused = new BffApi(mvc);
        serve(refused, REQUESTS_A_MINUTE);
        assertRateLimited(refused.get(coursePath).exchange(), "60");

        assertThat(new BffApi(mvc).get(coursePath)).hasStatusOk();
    }

    @Test
    void servesTheIpAgainOnceTheClockPassesTheWindow() {
        Instant start = clock.instant();
        BffApi bff = new BffApi(mvc);
        serve(bff, REQUESTS_A_MINUTE);

        clock.set(start.plusSeconds(59));
        assertRateLimited(bff.get(coursePath).exchange(), "1");
        clock.set(start.plusSeconds(60));
        serve(bff, REQUESTS_A_MINUTE);
        assertRateLimited(bff.get(coursePath).exchange(), "60");
    }

    @Test
    void countsTwoIpv6AddressesInOneSlash64Together() {
        String network = BffApi.newIpv6Slash64();
        BffApi one = new BffApi(mvc, network + "::1");
        BffApi other = new BffApi(mvc, network + ":ffff:ffff:ffff:fffe");
        serve(one, REQUESTS_A_MINUTE / 2);
        serve(other, REQUESTS_A_MINUTE / 2);

        assertRateLimited(one.get(coursePath).exchange(), "60");
        assertRateLimited(other.get(coursePath).exchange(), "60");
    }

    @Test
    void countsIpv6AddressesInDifferentSlash64sApart() {
        BffApi refused = new BffApi(mvc, BffApi.newIpv6Slash64() + "::1");
        serve(refused, REQUESTS_A_MINUTE);
        assertRateLimited(refused.get(coursePath).exchange(), "60");

        assertThat(new BffApi(mvc, BffApi.newIpv6Slash64() + "::1").get(coursePath)).hasStatusOk();
    }

    @Test
    void neverCountsTheAdmin() {
        assertThat(IntStream.rangeClosed(1, REQUESTS_A_MINUTE + 1)
                .mapToObj(request -> mvc.get().uri("/v1/admin/courses/" + courseId)
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .exchange().getResponse().getStatus()))
                .containsOnly(HttpStatus.OK.value());
    }

    /** Sends the requests, each of which must be served. */
    private void serve(BffApi bff, int requests) {
        assertThat(IntStream.range(0, requests)
                .mapToObj(request -> bff.get(coursePath).exchange().getResponse().getStatus()))
                .as("the statuses of %d requests from %s", requests, bff.clientIp())
                .containsOnly(HttpStatus.OK.value());
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
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(coursePath, clock.instant()));
    }
}
