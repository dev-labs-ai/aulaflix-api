package com.devlabs.aulaflix.config;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;

/**
 * The cheapest ways to learn who has an Account get the strictest limits per client IP (ADR 0005): the email look-up
 * and sign-in together, 60 an hour, and sign-up, 10 a day. Every request counts, whatever it answers.
 */
class AccountFlowLimitTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int LOOK_UPS_AND_SIGN_INS_AN_HOUR = 60;
    private static final int SIGN_UPS_A_DAY = 10;

    @Test
    void refusesThe61stLookUpOrSignInFromOneIpWithinAnHour() {
        String email = newEmail();
        new StudentApi(mvc).signedUp(email, PASSWORD);
        StudentApi students = new StudentApi(mvc);
        assertThat(statuses(LOOK_UPS_AND_SIGN_INS_AN_HOUR / 2, request -> students.lookUp(email)))
                .containsOnly(HttpStatus.OK.value());
        assertThat(statuses(LOOK_UPS_AND_SIGN_INS_AN_HOUR / 2, request -> students.signIn(email, PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());

        assertRateLimited(students.lookUp(email), "/v1/account-lookups", "3600");
        assertRateLimited(students.signIn(email, PASSWORD), "/v1/sessions", "3600");
    }

    @Test
    void countsEveryLookUpAndSignInWhateverItAnswers() {
        StudentApi students = new StudentApi(mvc);
        assertThat(statuses(20, request -> students.lookUp("not an email")))
                .containsOnly(HttpStatus.BAD_REQUEST.value());
        assertThat(statuses(20, request -> students.signIn(newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.BAD_REQUEST.value());
        assertThat(statuses(20, request -> students.lookUp(newEmail()))).containsOnly(HttpStatus.OK.value());

        assertRateLimited(students.lookUp(newEmail()), "/v1/account-lookups", "3600");
    }

    @Test
    void servesTheIpAgainOnceTheClockPassesTheHour() {
        Instant start = clock.instant();
        StudentApi students = new StudentApi(mvc);
        assertThat(statuses(LOOK_UPS_AND_SIGN_INS_AN_HOUR, request -> students.lookUp(newEmail())))
                .containsOnly(HttpStatus.OK.value());

        clock.set(start.plus(Duration.ofHours(1)).minusSeconds(1));
        assertRateLimited(students.lookUp(newEmail()), "/v1/account-lookups", "1");
        clock.set(start.plus(Duration.ofHours(1)));
        assertThat(students.lookUp(newEmail())).hasStatusOk();
    }

    @Test
    void servesAnotherIpWhileOneIsRefused() {
        StudentApi refused = new StudentApi(mvc);
        assertThat(statuses(LOOK_UPS_AND_SIGN_INS_AN_HOUR, request -> refused.lookUp(newEmail())))
                .containsOnly(HttpStatus.OK.value());
        assertRateLimited(refused.lookUp(newEmail()), "/v1/account-lookups", "3600");

        assertThat(new StudentApi(mvc).lookUp(newEmail())).hasStatusOk();
    }

    @Test
    void refusesThe11thSignUpFromOneIpWithinADay() {
        Instant start = clock.instant();
        StudentApi students = new StudentApi(mvc);
        assertThat(statuses(SIGN_UPS_A_DAY / 2, request -> students.signUp("Bia", newEmail(), "short")))
                .containsOnly(HttpStatus.BAD_REQUEST.value());
        assertThat(statuses(SIGN_UPS_A_DAY / 2, request -> students.signUp("Bia", newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());

        clock.set(start.plus(Duration.ofDays(1)).minusSeconds(1));
        assertRateLimited(students.signUp("Bia", newEmail(), PASSWORD), "/v1/accounts", "1");
        clock.set(start.plus(Duration.ofDays(1)));
        assertThat(students.signUp("Bia", newEmail(), PASSWORD)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void countsSignUpsApartFromLookUpsAndSignIns() {
        StudentApi students = new StudentApi(mvc);
        assertThat(statuses(LOOK_UPS_AND_SIGN_INS_AN_HOUR, request -> students.lookUp(newEmail())))
                .containsOnly(HttpStatus.OK.value());

        assertThat(statuses(SIGN_UPS_A_DAY, request -> students.signUp("Bia", newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());
        assertRateLimited(students.signUp("Bia", newEmail(), PASSWORD), "/v1/accounts", "86400");
    }

    @Test
    void neverCountsTheRestOfTheAccountFlows() {
        BffApi bff = new BffApi(mvc);
        StudentApi students = new StudentApi(bff);
        String token = students.signedUp(newEmail(), PASSWORD);

        assertThat(statuses(LOOK_UPS_AND_SIGN_INS_AN_HOUR + 1, request -> students.account(token)))
                .containsOnly(HttpStatus.OK.value());
        assertThat(students.lookUp(newEmail())).hasStatusOk();
    }

    private static List<Integer> statuses(int requests, IntFunction<MvcTestResult> request) {
        return IntStream.range(0, requests)
                .mapToObj(request)
                .map(result -> result.getResponse().getStatus())
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
}
