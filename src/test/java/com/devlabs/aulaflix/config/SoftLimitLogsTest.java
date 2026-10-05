package com.devlabs.aulaflix.config;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static com.github.tomakehurst.wiremock.client.WireMock.serviceUnavailable;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.Turnstile;

/**
 * A client IP crossing a soft limit is logged once per window, at WARN, with the operation, the kind of limit and the
 * IP: the requests it then makes without a token, and the tokens it sends, add nothing. No email goes into the log, nor
 * any token, nor Turnstile's secret.
 */
@ExtendWith(OutputCaptureExtension.class)
class SoftLimitLogsTest extends IntegrationTest {

    private static final int LOOK_UPS_AND_SIGN_INS = 10;

    @Autowired
    private Turnstile turnstile;

    @Test
    void warnsOncePerIpAndWindowAndNeverWithAnEmailOrAToken(CapturedOutput output) {
        Instant start = clock.instant();
        BffApi bff = new BffApi(mvc);
        String email = newEmail();
        String token = Turnstile.newToken();
        String rejected = Turnstile.newToken();
        turnstile.reject(rejected);

        List<String> firstWindow = warningsWhileCrossing(bff, email, token, rejected, output);
        clock.set(start.plus(Duration.ofMinutes(15)));
        List<String> secondWindow = warningsWhileCrossing(bff, email, Turnstile.newToken(), rejected, output);

        assertThat(List.of(firstWindow, secondWindow)).allSatisfy(warnings -> assertThat(warnings).singleElement()
                .asString().contains("look-ups-and-sign-ins", bff.clientIp()).containsIgnoringCase("soft"));
        assertThat(output.getAll()).doesNotContainIgnoringCase(email).doesNotContain(token, rejected,
                Turnstile.SECRET_KEY);
    }

    @Test
    void warnsWhySiteverifyFailedWithoutTheTokenNorTheSecret(CapturedOutput output) {
        BffApi bff = new BffApi(mvc);
        StudentApi students = new StudentApi(bff);
        assertThat(IntStream.range(0, LOOK_UPS_AND_SIGN_INS)
                .mapToObj(request -> students.lookUp(newEmail()).getResponse().getStatus()))
                .containsOnly(HttpStatus.OK.value());
        String token = Turnstile.newToken();
        turnstile.answer(token, serviceUnavailable());

        int before = output.getAll().length();
        assertThat(new StudentApi(bff.withCaptchaToken(token)).lookUp(newEmail()))
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(output.getAll().substring(before).lines()
                .filter(line -> line.contains("WARN") && line.contains("captcha-unavailable")))
                .singleElement().asString().contains("HTTP 503");
        assertThat(output.getAll()).doesNotContain(token, Turnstile.SECRET_KEY);
    }

    /** The WARN lines written while the IP spends its look-ups, then looks the email up past them, 3 different ways. */
    private List<String> warningsWhileCrossing(BffApi bff, String email, String token, String rejected,
                                               CapturedOutput output) {
        int before = output.getAll().length();
        StudentApi students = new StudentApi(bff);
        assertThat(IntStream.range(0, LOOK_UPS_AND_SIGN_INS)
                .mapToObj(request -> students.lookUp(email).getResponse().getStatus()))
                .containsOnly(HttpStatus.OK.value());
        assertThat(students.lookUp(email)).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(new StudentApi(bff.withCaptchaToken(rejected)).lookUp(email))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(new StudentApi(bff.withCaptchaToken(token)).lookUp(email)).hasStatusOk();
        return output.getAll().substring(before).lines().filter(line -> line.contains("WARN")).toList();
    }
}
