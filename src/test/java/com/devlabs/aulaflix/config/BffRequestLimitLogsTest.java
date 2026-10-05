package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.service.AccountService;

/**
 * A key past its limit is logged once per window, at WARN, with the operation, the kind of limit and the IP: one line
 * per refused request would let a flood fill the log. No email goes into it, even one the refused requests carry.
 */
@ExtendWith(OutputCaptureExtension.class)
class BffRequestLimitLogsTest extends IntegrationTest {

    private static final int REQUESTS_A_MINUTE = 600;
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    private String coursePath;

    /** The requests read one Course, which costs the same however many Courses the other tests have made. */
    @BeforeEach
    void announceACourse() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        String slug = AdminCourses.newSlug();
        new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD)).announced(slug);
        coursePath = "/v1/courses/" + slug;
    }

    @Test
    void warnsOncePerIpAndWindowAndNeverWithAnEmail(CapturedOutput output) {
        Instant start = clock.instant();
        BffApi bff = new BffApi(mvc);
        String email = "ana-" + UUID.randomUUID() + "@example.com";

        serve(bff);
        List<String> firstWindow = warningsWhileRefusing(bff, email, output);
        clock.set(start.plusSeconds(60));
        serve(bff);
        List<String> secondWindow = warningsWhileRefusing(bff, email, output);

        assertThat(List.of(firstWindow, secondWindow)).allSatisfy(warnings -> assertThat(warnings).singleElement()
                .asString().contains("bff-requests", bff.clientIp()).containsIgnoringCase("hard"));
        assertThat(output.getAll()).doesNotContainIgnoringCase(email);
    }

    private void serve(BffApi bff) {
        assertThat(IntStream.range(0, REQUESTS_A_MINUTE)
                .mapToObj(request -> bff.get(coursePath).exchange().getResponse().getStatus()))
                .containsOnly(HttpStatus.OK.value());
    }

    /** The WARN lines written while three requests, each with an email, are refused. */
    private List<String> warningsWhileRefusing(BffApi bff, String email, CapturedOutput output) {
        int before = output.getAll().length();
        assertThat(IntStream.range(0, 3)
                .mapToObj(request -> bff.get(coursePath + "?email=" + email).exchange().getResponse().getStatus()))
                .containsOnly(HttpStatus.TOO_MANY_REQUESTS.value());
        return output.getAll().substring(before).lines().filter(line -> line.contains("WARN")).toList();
    }
}
