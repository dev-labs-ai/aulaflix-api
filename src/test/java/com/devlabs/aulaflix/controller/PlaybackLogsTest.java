package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * A playback URL is a bearer token until it expires, so no log line may carry it, nor its signature, whatever logs
 * it. An IP past its limit on playback without a session is logged once per window, at WARN, with the limit and the
 * IP.
 */
@ExtendWith(OutputCaptureExtension.class)
class PlaybackLogsTest extends IntegrationTest {

    private static final int PLAYS_AN_HOUR = 30;
    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    private String freeLesson;

    @BeforeEach
    void launchACourse() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        AdminCourses courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos);
        freeLesson = "/v1/lessons/%d/playback".formatted(courses.freeLessonOf(courses.onSale(newSlug())));
    }

    @Test
    void logsNoPlaybackUrl(CapturedOutput output) {
        MvcTestResult playback = new BffApi(mvc).get(freeLesson).exchange();

        assertThat(playback).hasStatusOk();
        String url = JsonPath.read(body(playback), "$.url");
        assertThat(output.getAll()).isNotBlank()
                .doesNotContain(url, URI.create(url).getRawQuery(), signatureOf(url));
    }

    @Test
    void warnsOncePerIpAndWindowWhenPlaybackWithoutASessionIsRefused(CapturedOutput output) {
        Instant start = clock.instant();
        BffApi bff = new BffApi(mvc);

        play(bff);
        List<String> firstWindow = warningsWhileRefusing(bff, output);
        clock.set(start.plusSeconds(3600));
        play(bff);
        List<String> secondWindow = warningsWhileRefusing(bff, output);

        assertThat(List.of(firstWindow, secondWindow)).allSatisfy(warnings -> assertThat(warnings).singleElement()
                .asString().contains("visitor-playback", bff.clientIp()).containsIgnoringCase("hard"));
    }

    private void play(BffApi bff) {
        assertThat(IntStream.range(0, PLAYS_AN_HOUR)
                .mapToObj(request -> bff.get(freeLesson).exchange().getResponse().getStatus()))
                .containsOnly(HttpStatus.OK.value());
    }

    /** The WARN lines written while three plays are refused. */
    private List<String> warningsWhileRefusing(BffApi bff, CapturedOutput output) {
        int before = output.getAll().length();
        assertThat(IntStream.range(0, 3)
                .mapToObj(request -> bff.get(freeLesson).exchange().getResponse().getStatus()))
                .containsOnly(HttpStatus.TOO_MANY_REQUESTS.value());
        return output.getAll().substring(before).lines().filter(line -> line.contains("WARN")).toList();
    }

    private static String signatureOf(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .filter(parameter -> parameter.startsWith("X-Amz-Signature="))
                .map(parameter -> parameter.substring("X-Amz-Signature=".length()))
                .findFirst()
                .orElseThrow();
    }
}
