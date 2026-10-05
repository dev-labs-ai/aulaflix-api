package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static com.devlabs.aulaflix.StoredVideos.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/** The Free lesson's playback URL, over real HTTP against AIStor, as a Visitor's {@code <video>} element plays it. */
class PlaybackIT extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    private final RestTestClient http = RestTestClient.bindToServer().build();

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    @Test
    void theFreeLessonsUrlPlaysItsVideoWholeAndInRangesWithoutASession() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        AdminCourses courses = new AdminCourses(mvc, new AdminApi(mvc).sessionToken(email, PASSWORD), storedVideos);
        long freeLesson = courses.freeLessonOf(courses.onSale(newSlug()));
        byte[] video = fixture("three-seconds.mp4");

        MvcTestResult playback = new BffApi(mvc).get("/v1/lessons/%d/playback".formatted(freeLesson)).exchange();

        assertThat(playback).hasStatusOk();
        URI url = URI.create(JsonPath.read(body(playback), "$.url"));
        http.get().uri(url).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.valueOf("video/mp4"))
                .expectBody(byte[].class).isEqualTo(video);
        http.get().uri(url).header(HttpHeaders.RANGE, "bytes=1000-1999").exchange()
                .expectStatus().isEqualTo(HttpStatus.PARTIAL_CONTENT)
                .expectBody(byte[].class).isEqualTo(Arrays.copyOfRange(video, 1000, 2000));
    }
}
