package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.StoredVideos.fixture;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.devlabs.aulaflix.AdminApi;
import com.devlabs.aulaflix.AdminCourses;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * A signed URL is a bearer token, so no log line may carry one, nor its signature, whatever logs it: the application,
 * the framework or the storage's SDK. The Admin's email, name and token stay out too. And each Admin change leaves one
 * INFO line naming the Admin and the Lesson, and a refusal none.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminVideoLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private final Logger applicationLogger = (Logger) LoggerFactory.getLogger("com.devlabs.aulaflix");

    private String token;

    @BeforeEach
    void listenToTheApplicationsLogs() {
        appender.start();
        applicationLogger.addAppender(appender);
    }

    @AfterEach
    void stopListening() {
        applicationLogger.detachAppender(appender);
    }

    @Test
    void logsOneInfoLineNamingTheAdminAndTheLessonForEachChangeAndNoneForAReadOrARefusal() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        long admin = accounts.createAdmin(email, "Ana", PASSWORD).id();
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
        long lesson = new AdminCourses(mvc, token).lessonOfANewDraft();

        Logged upload = logged(() -> send(HttpMethod.POST, "/v1/admin/lessons/%d/video-uploads".formatted(lesson),
                ""));
        assertNamesOnly(upload.infoLines(), admin, lesson);
        String objectKey = JsonPath.read(body(upload.response()), "$.objectKey");
        assertThat(logged(() -> link(lesson, objectKey)).infoLines()).isEmpty();
        storedVideos.put(objectKey, fixture("three-seconds.mp4"));
        assertNamesOnly(logged(() -> link(lesson, objectKey)).infoLines(), admin, lesson);
        assertThat(logged(() -> send(HttpMethod.GET, "/v1/admin/lessons/%d/playback".formatted(lesson), ""))
                .infoLines()).isEmpty();
    }

    @Test
    void logsNoSignedUrlNorAnyPersonalDataOfTheAdminNorTheirToken(CapturedOutput output) {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        String name = "Ana " + UUID.randomUUID();
        accounts.createAdmin(email, name, PASSWORD);
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
        AdminCourses courses = new AdminCourses(mvc, token);
        long course = courses.draft(AdminCourses.newSlug());
        long lesson = courses.addLesson(courses.addModule(course, "Fundamentos"), "O que é uma API", "api");

        MvcTestResult first = send(HttpMethod.POST, "/v1/admin/lessons/%d/video-uploads".formatted(lesson), "");
        String firstKey = JsonPath.read(body(first), "$.objectKey");
        storedVideos.put(firstKey, fixture("three-seconds.mp4"));
        link(lesson, firstKey);
        MvcTestResult second = send(HttpMethod.POST, "/v1/admin/lessons/%d/video-uploads".formatted(lesson), "");
        String secondKey = JsonPath.read(body(second), "$.objectKey");
        link(lesson, secondKey);
        storedVideos.put(secondKey, fixture("five-seconds.mp4"));
        link(lesson, secondKey);
        MvcTestResult playback = send(HttpMethod.GET, "/v1/admin/lessons/%d/playback".formatted(lesson), "");
        send(HttpMethod.DELETE, "/v1/admin/courses/" + course, "");

        List<String> signedUrls = List.of(JsonPath.read(body(first), "$.uploadUrl"),
                JsonPath.read(body(second), "$.uploadUrl"), JsonPath.read(body(playback), "$.url"));
        assertThat(output.getAll()).isNotBlank()
                .doesNotContainIgnoringCase(email)
                .doesNotContain(name, token);
        signedUrls.forEach(url -> assertThat(output.getAll())
                .doesNotContain(url, URI.create(url).getRawQuery(), signatureOf(url)));
    }

    /** The Admin is told only that the file is not an MP4; the log says why, for whoever looks into it. */
    @Test
    void logsWhyAFileIsNotAnMp4AtWarn() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        accounts.createAdmin(email, "Ana", PASSWORD);
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
        long lesson = new AdminCourses(mvc, token).lessonOfANewDraft();
        MvcTestResult upload = send(HttpMethod.POST, "/v1/admin/lessons/%d/video-uploads".formatted(lesson), "");
        String objectKey = JsonPath.read(body(upload), "$.objectKey");
        storedVideos.put(objectKey, fixture("quicktime.mov"));
        appender.list.clear();

        link(lesson, objectKey);

        assertThat(appender.list).filteredOn(line -> line.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(line -> assertThat(line.getFormattedMessage()).isEqualTo(
                        "Refused PUT /v1/admin/lessons/%d/video: video-not-mp4; the file is a QuickTime movie"
                                .formatted(lesson)));
    }

    /** One line, whose values are exactly the ids given. */
    private static void assertNamesOnly(List<ILoggingEvent> lines, Object... ids) {
        assertThat(lines).singleElement()
                .satisfies(line -> assertThat(line.getArgumentArray()).containsExactlyInAnyOrder(ids));
    }

    /** The answer to the request, with the INFO lines the application logged while answering it. */
    private Logged logged(Supplier<MvcTestResult> request) {
        appender.list.clear();
        MvcTestResult response = request.get();
        return new Logged(response, appender.list.stream().filter(line -> line.getLevel() == Level.INFO).toList());
    }

    private record Logged(MvcTestResult response, List<ILoggingEvent> infoLines) {
    }

    private MvcTestResult link(long lesson, String objectKey) {
        return send(HttpMethod.PUT, "/v1/admin/lessons/%d/video".formatted(lesson),
                "{\"objectKey\": \"%s\"}".formatted(objectKey));
    }

    private MvcTestResult send(HttpMethod method, String path, String body) {
        return mvc.perform(MockMvcRequestBuilders.request(method, path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String signatureOf(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .filter(parameter -> parameter.startsWith("X-Amz-Signature="))
                .map(parameter -> parameter.substring("X-Amz-Signature=".length()))
                .findFirst()
                .orElseThrow();
    }
}
