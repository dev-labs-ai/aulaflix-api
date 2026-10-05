package com.devlabs.aulaflix.config;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.Turnstile;
import com.github.tomakehurst.wiremock.http.Fault;

/**
 * Past a soft limit per client IP, each request needs a fresh CAPTCHA token, which the API verifies with Turnstile's
 * {@code siteverify}: the email look-up and sign-in together, 10 within 15 minutes; sign-up, 3 an hour; the reset-code
 * request, 5 an hour. Below it, no request needs one.
 */
class SoftLimitTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final int LOOK_UPS_AND_SIGN_INS = 10;
    private static final int SIGN_UPS = 3;
    private static final int RESET_CODE_REQUESTS = 5;
    private static final int RESET_CODE_REQUESTS_A_DAY = 20;

    @Autowired
    private Turnstile turnstile;

    @Test
    void asksForACaptchaFromThe11thLookUpOrSignInWithin15Minutes() {
        String email = newEmail();
        new StudentApi(mvc).signedUp(email, PASSWORD);
        BffApi bff = new BffApi(mvc);
        StudentApi students = new StudentApi(bff);
        assertThat(statuses(LOOK_UPS_AND_SIGN_INS / 2, request -> students.lookUp(email)))
                .containsOnly(HttpStatus.OK.value());
        assertThat(statuses(LOOK_UPS_AND_SIGN_INS / 2, request -> students.signIn(email, PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());

        assertCaptchaRequired(students.lookUp(email), "/v1/account-lookups");
        assertCaptchaRequired(students.signIn(email, PASSWORD), "/v1/sessions");
    }

    @Test
    void servesALookUpOrSignInPastTheSoftLimitWithATokenTurnstileAccepts() {
        String email = newEmail();
        new StudentApi(mvc).signedUp(email, PASSWORD);
        BffApi bff = new BffApi(mvc);
        spendLookUps(bff);

        assertThat(new StudentApi(bff.solvingCaptchas()).lookUp(email)).hasStatusOk()
                .bodyJson().isStrictlyEqualTo("""
                        {"exists": true}""");
        assertThat(new StudentApi(bff.solvingCaptchas()).signIn(email, PASSWORD)).hasStatus(HttpStatus.CREATED);
        assertThat(new StudentApi(bff.withCaptchaToken("x".repeat(2048))).lookUp(email)).hasStatusOk();
    }

    @Test
    void asksAgainForACaptchaWhenTurnstileRejectsTheToken() {
        BffApi bff = new BffApi(mvc);
        spendLookUps(bff);
        String token = Turnstile.newToken();
        turnstile.reject(token);

        assertCaptchaRequired(new StudentApi(bff.withCaptchaToken(token)).lookUp(newEmail()), "/v1/account-lookups");
        assertCaptchaRequired(new StudentApi(bff.withCaptchaToken(" ")).lookUp(newEmail()), "/v1/account-lookups");
        assertCaptchaRequired(new StudentApi(bff.withCaptchaToken("x".repeat(2049))).lookUp(newEmail()),
                "/v1/account-lookups");
    }

    @Test
    void sendsTurnstileTheSecretTheTokenAndTheClientIp() {
        BffApi bff = new BffApi(mvc);
        spendLookUps(bff);
        String token = Turnstile.newToken();

        assertThat(new StudentApi(bff.withCaptchaToken(token)).lookUp(newEmail())).hasStatusOk();
        assertThat(turnstile.verificationsOf(token)).singleElement().isEqualTo(
                Map.of("secret", Turnstile.SECRET_KEY, "response", token, "remoteip", bff.clientIp()));
    }

    @Test
    void neitherAsksForNorVerifiesATokenBelowTheSoftLimit() {
        String token = Turnstile.newToken();
        turnstile.reject(token);
        StudentApi students = new StudentApi(new BffApi(mvc).withCaptchaToken(token));

        assertThat(statuses(LOOK_UPS_AND_SIGN_INS, request -> students.lookUp(newEmail())))
                .containsOnly(HttpStatus.OK.value());
        assertThat(turnstile.verificationsOf(token)).isEmpty();
    }

    @Test
    void servesTheIpWithoutACaptchaOnceThe15MinutesEnd() {
        Instant start = clock.instant();
        BffApi bff = new BffApi(mvc);
        spendLookUps(bff);
        StudentApi students = new StudentApi(bff);

        clock.set(start.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertCaptchaRequired(students.lookUp(newEmail()), "/v1/account-lookups");
        clock.set(start.plus(Duration.ofMinutes(15)));
        assertThat(students.lookUp(newEmail())).hasStatusOk();
    }

    @Test
    void servesAnotherIpWithoutACaptchaWhileOneNeedsIt() {
        spendLookUps(new BffApi(mvc));

        assertThat(new StudentApi(mvc).lookUp(newEmail())).hasStatusOk();
    }

    @Test
    void asksForACaptchaFromThe4thSignUpWithinAnHour() {
        BffApi bff = new BffApi(mvc);
        StudentApi students = new StudentApi(bff);
        assertThat(statuses(SIGN_UPS, request -> students.signUp("Bia", newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());

        assertCaptchaRequired(students.signUp("Bia", newEmail(), PASSWORD), "/v1/accounts");
        assertThat(new StudentApi(bff.solvingCaptchas()).signUp("Bia", newEmail(), PASSWORD))
                .hasStatus(HttpStatus.CREATED);
        String rejected = Turnstile.newToken();
        turnstile.reject(rejected);
        assertCaptchaRequired(new StudentApi(bff.withCaptchaToken(rejected)).signUp("Bia", newEmail(), PASSWORD),
                "/v1/accounts");
    }

    @Test
    void asksForACaptchaFromThe6thResetCodeRequestWithinAnHour() {
        BffApi bff = new BffApi(mvc);
        StudentApi students = new StudentApi(bff);
        assertThat(statuses(RESET_CODE_REQUESTS, request -> students.requestResetCode(newEmail())))
                .containsOnly(HttpStatus.NO_CONTENT.value());

        assertCaptchaRequired(students.requestResetCode(newEmail()), "/v1/password-reset-codes");
        assertThat(new StudentApi(bff.solvingCaptchas()).requestResetCode(newEmail()))
                .hasStatus(HttpStatus.NO_CONTENT);
        String rejected = Turnstile.newToken();
        turnstile.reject(rejected);
        assertCaptchaRequired(new StudentApi(bff.withCaptchaToken(rejected)).requestResetCode(newEmail()),
                "/v1/password-reset-codes");
    }

    @Test
    void refusesWith503WhenSiteverifyStaysSilentPastTheTimeout() {
        BffApi bff = new BffApi(mvc);
        spendSignUps(bff);
        String token = Turnstile.newToken();
        turnstile.answer(token, okJson("""
                {"success": true}""").withFixedDelay((int) Turnstile.TIMEOUT.multipliedBy(2).toMillis()));

        assertCaptchaUnavailable(new StudentApi(bff.withCaptchaToken(token)).signUp("Bia", newEmail(), PASSWORD),
                "/v1/accounts");
    }

    @Test
    void refusesWith503WhenSiteverifyFails() {
        BffApi bff = new BffApi(mvc);
        spendSignUps(bff);
        String failing = Turnstile.newToken();
        turnstile.answer(failing, serverError());
        String reset = Turnstile.newToken();
        turnstile.answer(reset, aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));
        String garbled = Turnstile.newToken();
        turnstile.answer(garbled, ok("not json"));

        assertThat(List.of(failing, reset, garbled)).allSatisfy(token -> assertCaptchaUnavailable(
                new StudentApi(bff.withCaptchaToken(token)).signUp("Bia", newEmail(), PASSWORD), "/v1/accounts"));
    }

    @Test
    void countsRequestsWithASolvedCaptchaAgainstTheHardLimit() {
        BffApi bff = new BffApi(mvc);
        StudentApi solving = new StudentApi(bff.solvingCaptchas());
        assertThat(statuses(RESET_CODE_REQUESTS_A_DAY, request -> solving.requestResetCode(newEmail())))
                .containsOnly(HttpStatus.NO_CONTENT.value());

        assertRateLimited(solving.requestResetCode(newEmail()));
        assertRateLimited(new StudentApi(bff).requestResetCode(newEmail()));
    }

    private void spendLookUps(BffApi bff) {
        StudentApi students = new StudentApi(bff);
        assertThat(statuses(LOOK_UPS_AND_SIGN_INS, request -> students.lookUp(newEmail())))
                .containsOnly(HttpStatus.OK.value());
    }

    private void spendSignUps(BffApi bff) {
        StudentApi students = new StudentApi(bff);
        assertThat(statuses(SIGN_UPS, request -> students.signUp("Bia", newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());
    }

    private void assertRateLimited(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.RETRY_AFTER, Long.toString(Duration.ofDays(1).toSeconds()))
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/rate-limited");
    }

    private void assertCaptchaUnavailable(MvcTestResult result, String path) {
        assertThat(result).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.RETRY_AFTER, "10")
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/captcha-unavailable",
                          "title": "CAPTCHA unavailable",
                          "status": 503,
                          "detail": "The CAPTCHA cannot be verified now. Try again in Retry-After seconds.",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(path, clock.instant()));
    }

    private static List<Integer> statuses(int requests, IntFunction<MvcTestResult> request) {
        return IntStream.range(0, requests)
                .mapToObj(request)
                .map(result -> result.getResponse().getStatus())
                .toList();
    }

    private void assertCaptchaRequired(MvcTestResult result, String path) {
        assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .doesNotContainHeader(HttpHeaders.RETRY_AFTER)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/captcha-required",
                          "title": "CAPTCHA required",
                          "status": 429,
                          "detail": "Solve the CAPTCHA, then send the request again with its token in AulaFlix-Captcha-Token.",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(path, clock.instant()));
    }
}
