package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.StoredVideos.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.AistorContainer;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * The Admin's side of a Lesson's video: an upload URL, the link, and the preview. Uploads are put in place the way the
 * Admin's curl leaves them, straight in AIStor; the presigned requests themselves run over real HTTP in
 * {@link AdminVideoIT}.
 */
class AdminVideoControllerTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final DateTimeFormatter SIGNING_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private static final Refusal NOT_MP4 = new Refusal("video-not-mp4", "Video not MP4",
            "The file is not an MP4: encode it with ffmpeg as a faststart H.264/AAC MP4.");
    private static final Refusal NOT_H264 = new Refusal("video-not-h264", "Video not H.264",
            "The video is not H.264 (avc1 or avc3): encode it again with -c:v libx264.");
    private static final Refusal NOT_AAC = new Refusal("audio-not-aac", "Audio not AAC",
            "The audio is not AAC: encode it again with -c:a aac, or without audio, with -an.");
    private static final Refusal TOO_SHORT = new Refusal("video-too-short", "Video too short",
            "The video lasts under a second, once rounded to the nearest second.");
    private static final Refusal NOT_FASTSTART = new Refusal("video-not-faststart", "Video not faststart",
            "The file's index does not come before its media: encode it again with -movflags +faststart.");

    @Autowired
    private AccountService accounts;

    @Autowired
    private AistorContainer storage;

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
    void issuesAnUploadUrlForANewKeyUnderTheLessonsPrefixSignedForAnHour() {
        long lesson = createLesson();

        MvcTestResult result = requestUpload(lesson);

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertNotStored(result);
        assertThat(result).bodyJson().extractingPath("$").asMap()
                .containsOnlyKeys("uploadUrl", "objectKey", "expiresAt", "requiredHeaders");
        assertThat(result).bodyJson().extractingPath("$.requiredHeaders").isEqualTo(Map.of("Content-Type",
                "video/mp4"));
        String objectKey = objectKeyOf(result);
        assertThat(objectKey).matches("lessons/%d/%s\\.mp4".formatted(lesson, UUID_PATTERN));
        String uploadUrl = JsonPath.read(body(result), "$.uploadUrl");
        assertThat(uploadUrl).startsWith(storage.endpoint() + "/videos/" + objectKey + "?");
        Map<String, String> query = queryOf(uploadUrl);
        assertThat(query).containsEntry("X-Amz-Expires", "3600")
                .containsEntry("X-Amz-SignedHeaders", "content-type;host")
                .doesNotContainKeys("x-amz-server-side-encryption", "X-Amz-Server-Side-Encryption");
        assertThat(query.get("X-Amz-Credential")).startsWith(key("read-write.access-key-id") + "/");
        assertThat(result).bodyJson().extractingPath("$.expiresAt")
                .isEqualTo(signedAt(query).plus(Duration.ofHours(1)).toString());
        assertThat(objectKeyOf(requestUpload(lesson))).isNotEqualTo(objectKey);
        assertThat(storedVideos.keysOf(lesson)).isEmpty();
    }

    @Test
    void linksAnUploadedVideoWithTheDurationReadFromTheFile() {
        long module = createModule(createCourse());
        long lesson = createLesson(module, "O que é uma API", "o-que-e-uma-api");
        String objectKey = upload(lesson, "three-seconds.mp4");

        MvcTestResult result = link(lesson, objectKey);

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "id": %d,
                          "moduleId": %d,
                          "title": "O que é uma API",
                          "slug": "o-que-e-uma-api",
                          "durationSeconds": 3,
                          "published": false
                        }""".formatted(lesson, module));
        assertThat(storedVideos.keysOf(lesson)).containsExactly(objectKey);
        assertThat(pathOf(playbackUrlOf(lesson))).isEqualTo("/videos/" + objectKey);
    }

    /** H.264 in either format, its parameter sets in the sample entry (avc1) or in band (avc3); audio is optional. */
    @ParameterizedTest(name = "{0}")
    @CsvSource({"H.264 as avc3 with AAC, avc3.mp4", "H.264 with no audio track, no-audio.mp4"})
    void linksAnyFaststartH264Mp4WithAacAudioOrNone(String description, String fixture) {
        long lesson = createLesson();
        String objectKey = upload(lesson, fixture);

        assertThat(link(lesson, objectKey)).hasStatusOk().bodyJson().extractingPath("$.durationSeconds").isEqualTo(2);
        assertThat(pathOf(playbackUrlOf(lesson))).isEqualTo("/videos/" + objectKey);
    }

    /** Linking never publishes: the Lesson can still be deleted, which only an unpublished Lesson can. */
    @Test
    void leavesTheLessonUnpublished() {
        long lesson = createLesson();
        assertThat(link(lesson, upload(lesson, "three-seconds.mp4"))).hasStatusOk()
                .bodyJson().doesNotHavePath("$.publishedAt")
                .extractingPath("$.published").isEqualTo(false);

        assertThat(delete("/v1/admin/lessons/" + lesson)).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void replacesThePublishedLessonsVideoAndKeepsItPublished() {
        long lesson = createLesson();
        AdminCourses courses = new AdminCourses(mvc, token, storedVideos);
        courses.linkVideo(lesson, "three-seconds.mp4");
        courses.publish(lesson);
        String objectKey = upload(lesson, "five-seconds.mp4");

        assertThat(link(lesson, objectKey)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
                {"durationSeconds": 5, "published": true}""");
        assertThat(storedVideos.keysOf(lesson)).containsExactly(objectKey);
    }

    @Test
    void linksAVideoToALessonOfACourseInAnyState() {
        long course = new AdminCourses(mvc, token).announced(AdminCourses.newSlug());
        long lesson = createLesson(createModule(course), "O que é uma API", "o-que-e-uma-api");

        assertThat(link(lesson, upload(lesson, "five-seconds.mp4"))).hasStatusOk()
                .bodyJson().extractingPath("$.durationSeconds").isEqualTo(5);
        assertThat(playback(lesson)).hasStatusOk();
    }

    @Test
    void replacingTheVideoUpdatesTheDurationAndDeletesEveryOtherObjectUnderTheLessonsPrefix() {
        long lesson = createLesson();
        long otherLesson = createLesson();
        String first = upload(lesson, "three-seconds.mp4");
        assertThat(link(lesson, first)).hasStatusOk();
        String abandoned = upload(lesson, "three-seconds.mp4");
        String otherLessonsVideo = upload(otherLesson, "three-seconds.mp4");
        String second = upload(lesson, "five-seconds.mp4");

        assertThat(link(lesson, second)).hasStatusOk().bodyJson().extractingPath("$.durationSeconds").isEqualTo(5);

        assertThat(storedVideos.keysOf(lesson)).containsExactly(second).doesNotContain(first, abandoned);
        assertThat(storedVideos.keysOf(otherLesson)).containsExactly(otherLessonsVideo);
        assertThat(pathOf(playbackUrlOf(lesson))).isEqualTo("/videos/" + second);
    }

    @Test
    void linkingTheVideoAlreadyLinkedKeepsIt() {
        long lesson = createLesson();
        String objectKey = upload(lesson, "three-seconds.mp4");
        assertThat(link(lesson, objectKey)).hasStatusOk();

        assertThat(link(lesson, objectKey)).hasStatusOk().bodyJson().extractingPath("$.durationSeconds").isEqualTo(3);

        assertThat(storedVideos.keysOf(lesson)).containsExactly(objectKey);
    }

    @Test
    void refusesAKeyIssuedForTheLessonWhoseObjectWasNeverUploaded() {
        long lesson = createLesson();
        String linked = upload(lesson, "three-seconds.mp4");
        assertThat(link(lesson, linked)).hasStatusOk();
        String neverUploaded = objectKeyOf(requestUpload(lesson));

        assertVideoNotFound(link(lesson, neverUploaded), lesson);

        assertThat(pathOf(playbackUrlOf(lesson))).isEqualTo("/videos/" + linked);
        assertThat(storedVideos.keysOf(lesson)).containsExactly(linked);
    }

    /**
     * Each key, stored or not, names an object the API never issued for this Lesson: {@code {lesson}} is its id and
     * {@code {other}} another Lesson's. Linking it changes nothing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("keysNotIssuedForTheLesson")
    void refusesAKeyNotIssuedForTheLessonAndChangesNothing(String description, String keyTemplate) {
        long lesson = createLesson();
        long otherLesson = createLesson();
        String linked = upload(lesson, "three-seconds.mp4");
        assertThat(link(lesson, linked)).hasStatusOk();
        String objectKey = keyTemplate.replace("{lesson}", Long.toString(lesson))
                .replace("{other}", Long.toString(otherLesson));
        storedVideos.put(objectKey, fixture("five-seconds.mp4"));

        assertVideoNotFound(link(lesson, objectKey), lesson);

        assertThat(pathOf(playbackUrlOf(lesson))).isEqualTo("/videos/" + linked);
        assertThat(link(lesson, linked)).bodyJson().extractingPath("$.durationSeconds").isEqualTo(3);
    }

    static Stream<Arguments> keysNotIssuedForTheLesson() {
        String uuid = "6f1c2c63-0f2e-4a8e-9a43-3a0d9c2e8b11";
        return Stream.of(
                Arguments.of("another Lesson's key", "lessons/{other}/" + uuid + ".mp4"),
                Arguments.of("a name that is not a UUID", "lessons/{lesson}/aula.mp4"),
                Arguments.of("an upper-case UUID", "lessons/{lesson}/" + uuid.toUpperCase() + ".mp4"),
                Arguments.of("another extension", "lessons/{lesson}/" + uuid + ".mov"),
                Arguments.of("a key nested deeper", "lessons/{lesson}/x/" + uuid + ".mp4"),
                Arguments.of("a key outside every Lesson", uuid + ".mp4"),
                Arguments.of("a prefix that only starts like the Lesson's", "lessons/{lesson}0/" + uuid + ".mp4"));
    }

    @Test
    void refusesAKeyThatClimbsOutOfTheLessonsPrefix() {
        long lesson = createLesson();
        long otherLesson = createLesson();
        String othersVideo = upload(otherLesson, "three-seconds.mp4");

        assertVideoNotFound(link(lesson, "lessons/%d/../%s".formatted(lesson, othersVideo)), lesson);
        assertVideoNotFound(link(lesson, othersVideo), lesson);

        assertThat(playback(lesson)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(storedVideos.keysOf(otherLesson)).containsExactly(othersVideo);
    }

    /**
     * Each file linking refuses, with the problem that tells the Admin how to encode it again. A refused link changes
     * nothing: the Lesson keeps its video and its duration, and no object is deleted, the refused upload included.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedFiles")
    void refusesAFileThatIsNotAFaststartH264AacMp4AndChangesNothing(String description, byte[] content,
                                                                      Refusal refusal) {
        long lesson = createLesson(createModule(createCourse()), "O que é uma API", "o-que-e-uma-api");
        String linked = upload(lesson, "three-seconds.mp4");
        assertThat(link(lesson, linked)).hasStatusOk();
        String refused = upload(lesson, content);

        assertProblem(link(lesson, refused), "/v1/admin/lessons/%d/video".formatted(lesson), HttpStatus.CONFLICT,
                refusal.name(), refusal.title(), refusal.detail());

        assertThat(pathOf(playbackUrlOf(lesson))).isEqualTo("/videos/" + linked);
        assertThat(storedDurationOf(lesson, "O que é uma API", "o-que-e-uma-api")).isEqualTo(3);
        assertThat(storedVideos.keysOf(lesson)).containsExactlyInAnyOrder(linked, refused);
    }

    static Stream<Arguments> refusedFiles() {
        return Stream.of(
                Arguments.of("a WebM", fixture("vp9.webm"), NOT_MP4),
                Arguments.of("a QuickTime movie of H.264 and AAC", fixture("quicktime.mov"), NOT_MP4),
                Arguments.of("an empty file", new byte[0], NOT_MP4),
                Arguments.of("a text file", "aula 1: o que é uma API".getBytes(StandardCharsets.UTF_8), NOT_MP4),
                Arguments.of("an MP4 whose index comes after its media", fixture("not-faststart.mp4"),
                        NOT_FASTSTART),
                Arguments.of("a fragmented MP4", fixture("fragmented.mp4"), NOT_FASTSTART),
                Arguments.of("an MP4 of HEVC", fixture("hevc.mp4"), NOT_H264),
                Arguments.of("an MP4 of MP3 audio", fixture("mp3-audio.mp4"), NOT_AAC),
                Arguments.of("an MP4 of Opus audio", fixture("opus-audio.mp4"), NOT_AAC),
                Arguments.of("an MP4 of 0.4 seconds", fixture("under-half-a-second.mp4"), TOO_SHORT));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"objectKey\": null}", "{\"objectKey\": \"  \"}"})
    void refusesALinkWithoutAKey(String request) {
        long lesson = createLesson();

        assertThat(put("/v1/admin/lessons/%d/video".formatted(lesson), request))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/invalid-request",
                          "title": "Invalid request",
                          "status": 400,
                          "detail": "One or more fields are invalid.",
                          "instance": "/v1/admin/lessons/%d/video",
                          "timestamp": "%s",
                          "errors": [{"field": "objectKey", "code": "required"}]
                        }""".formatted(lesson, clock.instant()));
    }

    @Test
    void previewsTheLinkedVideoThroughAUrlSignedWithTheReadOnlyKeyForFourHours() {
        long lesson = createLesson();
        String objectKey = upload(lesson, "three-seconds.mp4");
        assertThat(link(lesson, objectKey)).hasStatusOk();

        MvcTestResult result = playback(lesson);

        assertThat(result).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertNotStored(result);
        assertThat(result).bodyJson().extractingPath("$").asMap().containsOnlyKeys("url", "expiresAt");
        String url = JsonPath.read(body(result), "$.url");
        assertThat(url).startsWith(storage.endpoint() + "/videos/" + objectKey + "?");
        Map<String, String> query = queryOf(url);
        assertThat(query).containsEntry("X-Amz-Expires", "14400").containsEntry("X-Amz-SignedHeaders", "host");
        assertThat(query.get("X-Amz-Credential")).startsWith(key("read-only.access-key-id") + "/");
        assertThat(result).bodyJson().extractingPath("$.expiresAt")
                .isEqualTo(signedAt(query).plus(Duration.ofHours(4)).toString());
    }

    @Test
    void answersThatALessonWithoutAVideoHasNothingToPreview() {
        long lesson = createLesson();
        String path = "/v1/admin/lessons/%d/playback".formatted(lesson);
        assertThat(requestUpload(lesson)).hasStatusOk();

        assertProblem(playback(lesson), path, HttpStatus.NOT_FOUND, "video-not-linked", "Video not linked",
                "The Lesson has no video yet.");
    }

    @Test
    void deletingALessonDeletesItsObjects() {
        long module = createModule(createCourse());
        long lesson = createLesson(module, "O que é uma API", "o-que-e-uma-api");
        long kept = createLesson(module, "HTTP na prática", "http-na-pratica");
        assertThat(link(lesson, upload(lesson, "three-seconds.mp4"))).hasStatusOk();
        upload(lesson, "five-seconds.mp4");
        String keptVideo = upload(kept, "three-seconds.mp4");
        assertThat(link(kept, keptVideo)).hasStatusOk();

        assertThat(delete("/v1/admin/lessons/" + lesson)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(storedVideos.keysOf(lesson)).isEmpty();
        assertThat(storedVideos.keysOf(kept)).containsExactly(keptVideo);
    }

    @Test
    void deletingADraftCourseDeletesTheObjectsOfEachOfItsLessons() {
        long course = createCourse();
        long fundamentos = createModule(course);
        long rotas = createModule(course);
        long first = createLesson(fundamentos, "O que é uma API", "o-que-e-uma-api");
        long second = createLesson(rotas, "Rotas no Express", "rotas-no-express");
        long otherCoursesLesson = createLesson();
        assertThat(link(first, upload(first, "three-seconds.mp4"))).hasStatusOk();
        upload(second, "five-seconds.mp4");
        String otherCoursesVideo = upload(otherCoursesLesson, "three-seconds.mp4");

        assertThat(delete("/v1/admin/courses/" + course)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(storedVideos.keysOf(first)).isEmpty();
        assertThat(storedVideos.keysOf(second)).isEmpty();
        assertThat(storedVideos.keysOf(otherCoursesLesson)).containsExactly(otherCoursesVideo);
    }

    @Test
    void aDeletedLessonGetsNoUploadLinkOrPreview() {
        long lesson = createLesson();
        String objectKey = upload(lesson, "three-seconds.mp4");
        assertThat(delete("/v1/admin/lessons/" + lesson)).hasStatus(HttpStatus.NO_CONTENT);
        String path = "/v1/admin/lessons/" + lesson;

        assertLessonNotFound(requestUpload(lesson), path + "/video-uploads");
        assertLessonNotFound(link(lesson, objectKey), path + "/video");
        assertLessonNotFound(playback(lesson), path + "/playback");
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999999999999999", "0", "-1", "007", "1.5", "abc", "99999999999999999999"})
    void answersAnIdOfAnyShapeLikeAnUnknownOne(String id) {
        String path = "/v1/admin/lessons/" + id;

        assertLessonNotFound(post(path + "/video-uploads"), path + "/video-uploads");
        assertLessonNotFound(put(path + "/video", "{\"objectKey\": \"lessons/%s/x.mp4\"}".formatted(id)),
                path + "/video");
        assertLessonNotFound(get(path + "/playback"), path + "/playback");
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void refusesEveryEndpointWithoutASessionAndLinksNothing(HttpMethod method, String path) {
        long lesson = createLesson();
        String objectKey = upload(lesson, "three-seconds.mp4");

        MvcTestResult result = mvc.perform(MockMvcRequestBuilders.request(method, path.formatted(lesson))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\": \"%s\"}".formatted(objectKey)));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/unauthenticated");
        assertThat(playback(lesson)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("everyEndpoint")
    void refusesEveryEndpointOnceTheAdminSignedOut(HttpMethod method, String path) {
        long lesson = createLesson();
        String objectKey = upload(lesson, "three-seconds.mp4");
        String signedOut = token;
        new AdminApi(mvc).signOut(signedOut);

        MvcTestResult result = mvc.perform(MockMvcRequestBuilders.request(method, path.formatted(lesson))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + signedOut)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"objectKey\": \"%s\"}".formatted(objectKey)));

        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
                .bodyJson().extractingPath("$.type").isEqualTo("https://aulaflix.com.br/problems/unauthenticated");
    }

    static Stream<Arguments> everyEndpoint() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/v1/admin/lessons/%d/video-uploads"),
                Arguments.of(HttpMethod.PUT, "/v1/admin/lessons/%d/video"),
                Arguments.of(HttpMethod.GET, "/v1/admin/lessons/%d/playback"));
    }

    /** A signed URL is a bearer token, so no cache along the way may keep the answer that carries it. */
    private static void assertNotStored(MvcTestResult result) {
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
    }

    private void assertVideoNotFound(MvcTestResult result, long lesson) {
        assertProblem(result, "/v1/admin/lessons/%d/video".formatted(lesson), HttpStatus.CONFLICT,
                "video-not-found", "Video not found",
                "No video was uploaded under this key for this Lesson: request an upload URL, upload, then link.");
    }

    private void assertLessonNotFound(MvcTestResult result, String path) {
        assertProblem(result, path, HttpStatus.NOT_FOUND, "lesson-not-found", "Lesson not found",
                "No Lesson has this id.");
    }

    /** The whole ProblemDetail of a refusal that carries no extension. */
    private void assertProblem(MvcTestResult result, String path, HttpStatus status, String name, String title,
                               String detail) {
        assertThat(result).hasStatus(status)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().isStrictlyEqualTo("""
                        {
                          "type": "https://aulaflix.com.br/problems/%s",
                          "title": "%s",
                          "status": %d,
                          "detail": "%s",
                          "instance": "%s",
                          "timestamp": "%s"
                        }""".formatted(name, title, status.value(), detail, path, clock.instant()));
    }

    /** The key a new upload URL names, with the fixture stored under it as the Admin's curl would leave it. */
    private String upload(long lesson, String fixture) {
        return upload(lesson, fixture(fixture));
    }

    private String upload(long lesson, byte[] content) {
        String objectKey = objectKeyOf(requestUpload(lesson));
        storedVideos.put(objectKey, content);
        return objectKey;
    }

    /** The duration the Lesson keeps, as an edit that changes nothing answers it. */
    private int storedDurationOf(long lesson, String title, String slug) {
        MvcTestResult edited = put("/v1/admin/lessons/" + lesson,
                "{\"title\": \"%s\", \"slug\": \"%s\"}".formatted(title, slug));
        assertThat(edited).hasStatusOk();
        return JsonPath.read(body(edited), "$.durationSeconds");
    }

    private MvcTestResult requestUpload(long lesson) {
        return post("/v1/admin/lessons/%d/video-uploads".formatted(lesson));
    }

    private MvcTestResult link(long lesson, String objectKey) {
        return put("/v1/admin/lessons/%d/video".formatted(lesson), "{\"objectKey\": \"%s\"}".formatted(objectKey));
    }

    private MvcTestResult playback(long lesson) {
        return get("/v1/admin/lessons/%d/playback".formatted(lesson));
    }

    private String playbackUrlOf(long lesson) {
        MvcTestResult result = playback(lesson);
        assertThat(result).hasStatusOk();
        return JsonPath.read(body(result), "$.url");
    }

    private static String objectKeyOf(MvcTestResult upload) {
        assertThat(upload).hasStatusOk();
        return JsonPath.read(body(upload), "$.objectKey");
    }

    private String key(String name) {
        return storage.applicationProperties().get("aulaflix.storage." + name).get().toString();
    }

    private static String pathOf(String url) {
        return URI.create(url).getRawPath();
    }

    private static Map<String, String> queryOf(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
    }

    /** The instant the URL was signed at, to the second, which its validity counts from. */
    private static Instant signedAt(Map<String, String> query) {
        return LocalDateTime.parse(query.get("X-Amz-Date"), SIGNING_DATE).toInstant(ZoneOffset.UTC);
    }

    private long createLesson() {
        return new AdminCourses(mvc, token).lessonOfANewDraft();
    }

    private long createCourse() {
        return new AdminCourses(mvc, token).draft(AdminCourses.newSlug());
    }

    private long createModule(long course) {
        return new AdminCourses(mvc, token).addModule(course, "Fundamentos");
    }

    private long createLesson(long module, String title, String slug) {
        return new AdminCourses(mvc, token).addLesson(module, title, slug);
    }

    private MvcTestResult get(String path) {
        return mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult delete(String path) {
        return mvc.delete().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult post(String path) {
        return mvc.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    private MvcTestResult put(String path, String body) {
        return mvc.put().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    /** One of the problems a refused link answers. */
    private record Refusal(String name, String title, String detail) {
    }
}
