package com.devlabs.aulaflix.config;

import static com.devlabs.aulaflix.StudentApi.newEmail;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StudentApi;

/**
 * Past a global soft limit, every client IP needs a CAPTCHA for the rest of the window, even one that never crossed
 * its own: that is what catches a flood spread over many IPs. Every request of the operation counts against it, so
 * this class lowers the global limits in an application context of its own, where no other test counts, and each test
 * starts in a window of its own, however often it runs in that context.
 */
@ExtendWith(OutputCaptureExtension.class)
class GlobalSoftLimitTest extends IntegrationTest {

    private static final int GLOBAL = 4;
    private static final String PASSWORD = "correct horse battery";
    private static final Instant BASE = Instant.now();
    private static final AtomicInteger WINDOWS = new AtomicInteger();

    @DynamicPropertySource
    static void lowerTheGlobalSoftLimits(DynamicPropertyRegistry registry) {
        List.of("look-ups-and-sign-ins", "sign-ups", "password-reset-codes").forEach(operation ->
                registry.add("aulaflix.soft-limits.%s.global.requests".formatted(operation), () -> GLOBAL));
    }

    /**
     * A day after the start of every earlier test of this class, so in a window of its own. The clock moves only when
     * a test sets it, so each global counter opened its first window at the start of a test, a whole number of days
     * before this one: this test's window starts with it.
     */
    @BeforeEach
    void startInAWindowOfItsOwn() {
        clock.set(BASE.plus(Duration.ofDays(WINDOWS.incrementAndGet())));
    }

    @Test
    void asksEveryIpForACaptchaPastTheGlobalSoftLimitOnLookUpsAndSignIns(CapturedOutput output) {
        String email = newEmail();
        new StudentApi(new BffApi(mvc).solvingCaptchas()).signedUp(email, PASSWORD);
        assertThat(fromFreshIps(GLOBAL / 2, students -> students.lookUp(email))).containsOnly(HttpStatus.OK.value());
        assertThat(fromFreshIps(GLOBAL / 2, students -> students.signIn(email, PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());

        assertThat(fromFreshIps(2, students -> students.lookUp(email)))
                .containsOnly(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(fromFreshIps(2, students -> students.signIn(email, PASSWORD)))
                .containsOnly(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(new StudentApi(new BffApi(mvc).solvingCaptchas()).lookUp(email)).hasStatusOk();
        assertThat(globalTrips(output)).singleElement().asString().contains("look-ups-and-sign-ins");
    }

    @Test
    void asksEveryIpForACaptchaPastTheGlobalSoftLimitOnSignUps(CapturedOutput output) {
        assertThat(fromFreshIps(GLOBAL, students -> students.signUp("Bia", newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());

        assertThat(fromFreshIps(3, students -> students.signUp("Bia", newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(new StudentApi(new BffApi(mvc).solvingCaptchas()).signUp("Bia", newEmail(), PASSWORD))
                .hasStatus(HttpStatus.CREATED);
        assertThat(globalTrips(output)).singleElement().asString().contains("sign-ups");
    }

    @Test
    void asksEveryIpForACaptchaPastTheGlobalSoftLimitOnResetCodeRequests(CapturedOutput output) {
        assertThat(fromFreshIps(GLOBAL, students -> students.requestResetCode(newEmail())))
                .containsOnly(HttpStatus.NO_CONTENT.value());

        assertThat(fromFreshIps(3, students -> students.requestResetCode(newEmail())))
                .containsOnly(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(new StudentApi(new BffApi(mvc).solvingCaptchas()).requestResetCode(newEmail()))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(globalTrips(output)).singleElement().asString().contains("password-reset-codes");
    }

    @Test
    void servesEveryIpWithoutACaptchaOnceTheHourEnds() {
        Instant start = clock.instant();
        assertThat(fromFreshIps(GLOBAL + 1, students -> students.signUp("Bia", newEmail(), PASSWORD)))
                .endsWith(HttpStatus.TOO_MANY_REQUESTS.value());

        clock.set(start.plus(Duration.ofHours(1)).minusSeconds(1));
        assertThat(fromFreshIps(1, students -> students.signUp("Bia", newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.TOO_MANY_REQUESTS.value());
        clock.set(start.plus(Duration.ofHours(1)));
        assertThat(fromFreshIps(1, students -> students.signUp("Bia", newEmail(), PASSWORD)))
                .containsOnly(HttpStatus.CREATED.value());
    }

    /** The statuses of the requests, each from an IP of its own, without a CAPTCHA token. */
    private List<Integer> fromFreshIps(int requests, Function<StudentApi, MvcTestResult> request) {
        return IntStream.range(0, requests)
                .mapToObj(any -> request.apply(new StudentApi(mvc)))
                .map(result -> result.getResponse().getStatus())
                .toList();
    }

    private static List<String> globalTrips(CapturedOutput output) {
        return output.getAll().lines()
                .filter(line -> line.contains("WARN") && line.contains("Global soft limit"))
                .toList();
    }
}
