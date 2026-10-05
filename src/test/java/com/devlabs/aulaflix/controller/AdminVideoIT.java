package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.StoredVideos.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * The signed URLs themselves, over real HTTP against AIStor: the upload as the Admin's curl makes it, and playback as
 * a {@code <video>} element makes it, in ranges.
 */
class AdminVideoIT extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final MediaType MP4 = MediaType.valueOf("video/mp4");

    private final RestTestClient http = RestTestClient.bindToServer().build();

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    private String token;

    @BeforeEach
    void signInAnAdmin() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
    }

    @Test
    void uploadsThroughTheUploadUrlWithTheRequiredHeadersThenLinksTheUpload() {
        long lesson = new AdminCourses(mvc, token).lessonOfANewDraft();
        MvcTestResult upload = requestUpload(lesson);
        String objectKey = JsonPath.read(body(upload), "$.objectKey");
        String requiredContentType = JsonPath.read(body(upload), "$.requiredHeaders['Content-Type']");

        http.put().uri(urlOf(upload, "$.uploadUrl"))
                .header(HttpHeaders.CONTENT_TYPE, requiredContentType)
                .body(fixture("three-seconds.mp4"))
                .exchange()
                .expectStatus().isOk();

        assertThat(storedVideos.keysOf(lesson)).containsExactly(objectKey);
        assertThat(link(lesson, objectKey)).hasStatusOk().bodyJson().extractingPath("$.durationSeconds").isEqualTo(3);
    }

    @Test
    void refusesAnUploadThatDoesNotCarryTheSignedContentType() {
        long lesson = new AdminCourses(mvc, token).lessonOfANewDraft();
        URI uploadUrl = urlOf(requestUpload(lesson), "$.uploadUrl");

        assertRefusedBySignature(http.put().uri(uploadUrl).body(fixture("three-seconds.mp4")).exchange());
        assertRefusedBySignature(http.put().uri(uploadUrl).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(fixture("three-seconds.mp4")).exchange());

        assertThat(storedVideos.keysOf(lesson)).isEmpty();
    }

    @Test
    void playsTheLinkedVideoWholeAndInRanges() {
        long lesson = new AdminCourses(mvc, token).lessonOfANewDraft();
        byte[] video = fixture("five-seconds.mp4");
        String objectKey = uploadThroughTheApi(lesson, video);
        assertThat(link(lesson, objectKey)).hasStatusOk();
        URI playbackUrl = urlOf(playback(lesson), "$.url");

        http.get().uri(playbackUrl).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MP4)
                .expectBody(byte[].class).isEqualTo(video);
        http.get().uri(playbackUrl).header(HttpHeaders.RANGE, "bytes=1000-1999").exchange()
                .expectStatus().isEqualTo(HttpStatus.PARTIAL_CONTENT)
                .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes 1000-1999/" + video.length)
                .expectBody(byte[].class).isEqualTo(Arrays.copyOfRange(video, 1000, 2000));
    }

    /** Each URL is signed for its method and key alone: the upload URL reads nothing, and playback writes nothing. */
    @Test
    void neitherUrlServesTheOtherRequest() {
        long lesson = new AdminCourses(mvc, token).lessonOfANewDraft();
        MvcTestResult upload = requestUpload(lesson);
        URI uploadUrl = urlOf(upload, "$.uploadUrl");
        String objectKey = JsonPath.read(body(upload), "$.objectKey");
        http.put().uri(uploadUrl).contentType(MP4).body(fixture("three-seconds.mp4")).exchange()
                .expectStatus().isOk();
        assertThat(link(lesson, objectKey)).hasStatusOk();
        URI playbackUrl = urlOf(playback(lesson), "$.url");

        assertRefusedBySignature(http.get().uri(uploadUrl).exchange());
        assertRefusedBySignature(http.put().uri(playbackUrl).contentType(MP4).body(fixture("five-seconds.mp4"))
                .exchange());

        http.get().uri(playbackUrl).exchange().expectBody(byte[].class).isEqualTo(fixture("three-seconds.mp4"));
    }

    /**
     * The storage's own refusal of a request the signature does not cover: a 403 {@code SignatureDoesNotMatch} for a
     * header with another value, or a 400 {@code AccessDenied} for a request without a header the URL signed.
     */
    private static void assertRefusedBySignature(RestTestClient.ResponseSpec response) {
        response.expectStatus().is4xxClientError()
                .expectBody(String.class).value(error -> assertThat(error)
                        .containsPattern("<Code>(SignatureDoesNotMatch|AccessDenied)</Code>"));
    }

    private String uploadThroughTheApi(long lesson, byte[] video) {
        MvcTestResult upload = requestUpload(lesson);
        http.put().uri(urlOf(upload, "$.uploadUrl")).contentType(MP4).body(video).exchange()
                .expectStatus().isOk();
        return JsonPath.read(body(upload), "$.objectKey");
    }

    private MvcTestResult requestUpload(long lesson) {
        MvcTestResult upload = mvc.post().uri("/v1/admin/lessons/%d/video-uploads".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
        assertThat(upload).hasStatusOk();
        return upload;
    }

    private MvcTestResult link(long lesson, String objectKey) {
        return mvc.put().uri("/v1/admin/lessons/%d/video".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\": \"%s\"}".formatted(objectKey))
                .exchange();
    }

    private MvcTestResult playback(long lesson) {
        MvcTestResult playback = mvc.get().uri("/v1/admin/lessons/%d/playback".formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange();
        assertThat(playback).hasStatusOk();
        return playback;
    }

    /** The URL exactly as signed: re-encoding any of it would break the signature. */
    private static URI urlOf(MvcTestResult result, String path) {
        return URI.create(JsonPath.read(body(result), path));
    }
}
