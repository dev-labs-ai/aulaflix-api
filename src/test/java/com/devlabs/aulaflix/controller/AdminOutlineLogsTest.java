package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static org.assertj.core.api.Assertions.assertThat;

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
 * Logs must not become a leak, and they are the audit of every Admin change. This asserts what never appears: the
 * Admin's email, name and token, through every change to an outline and its refusals. And it asserts, whatever the
 * wording, that each change leaves one INFO line naming the Admin and what changed, and that a refusal leaves none.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminOutlineLogsTest extends IntegrationTest {

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
    void logsOneInfoLineNamingTheAdminAndWhatChangedForEachChangeAndNoneForARefusal() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        long admin = accounts.createAdmin(email, "Ana", PASSWORD).id();
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
        long course = Long.parseLong(idOf(send(HttpMethod.POST, "/v1/admin/courses", """
                {"slug": "curso-%s", "title": "Backend"}""".formatted(UUID.randomUUID()))));

        Logged added = logged(() -> send(HttpMethod.POST, "/v1/admin/courses/%d/modules".formatted(course),
                "{\"title\": \"Fundamentos\"}"));
        long module = Long.parseLong(idOf(added.response()));
        assertNamesOnly(added.infoLines(), admin, module, course);
        assertNamesOnly(infoLinesOf(() -> send(HttpMethod.PUT, "/v1/admin/modules/" + module,
                "{\"title\": \"Fundamentos de APIs\"}")), admin, module);
        Logged addedLesson = logged(() -> send(HttpMethod.POST, "/v1/admin/modules/%d/lessons".formatted(module),
                "{\"title\": \"API\", \"slug\": \"api\"}"));
        long lesson = Long.parseLong(idOf(addedLesson.response()));
        assertNamesOnly(addedLesson.infoLines(), admin, lesson, module);
        assertNamesOnly(infoLinesOf(() -> send(HttpMethod.PUT, "/v1/admin/lessons/" + lesson,
                "{\"title\": \"APIs\", \"slug\": \"apis\"}")), admin, lesson);
        assertNamesOnly(infoLinesOf(() -> send(HttpMethod.PUT, "/v1/admin/courses/%d/outline".formatted(course),
                "[{\"moduleId\": %d, \"lessonIds\": [%d]}]".formatted(module, lesson))), admin, course);

        assertThat(infoLinesOf(() -> send(HttpMethod.POST, "/v1/admin/modules/%d/lessons".formatted(module),
                "{\"title\": \"APIs\", \"slug\": \"apis\"}"))).isEmpty();
        assertThat(infoLinesOf(() -> send(HttpMethod.PUT, "/v1/admin/courses/%d/outline".formatted(course),
                "[]"))).isEmpty();
        assertThat(infoLinesOf(() -> send(HttpMethod.DELETE, "/v1/admin/modules/" + module, ""))).isEmpty();

        assertNamesOnly(infoLinesOf(() -> send(HttpMethod.DELETE, "/v1/admin/lessons/" + lesson, "")), admin,
                lesson);
        assertNamesOnly(infoLinesOf(() -> send(HttpMethod.DELETE, "/v1/admin/modules/" + module, "")), admin,
                module);
        assertThat(infoLinesOf(() -> send(HttpMethod.DELETE, "/v1/admin/modules/" + module, ""))).isEmpty();
    }

    @Test
    void logsOneInfoLineNamingTheAdminAndTheLessonForAPublicationAndNoneForARepeatOrARefusal() {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        long admin = accounts.createAdmin(email, "Ana", PASSWORD).id();
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
        AdminCourses courses = new AdminCourses(mvc, token, storedVideos);
        long lesson = courses.lessonOfANewDraft();
        String publication = "/v1/admin/lessons/%d/status".formatted(lesson);

        assertThat(infoLinesOf(() -> send(HttpMethod.PUT, publication, "{\"status\": \"PUBLISHED\"}"))).isEmpty();
        courses.linkVideo(lesson, "three-seconds.mp4");
        assertNamesOnly(infoLinesOf(() -> send(HttpMethod.PUT, publication, "{\"status\": \"PUBLISHED\"}")), admin,
                lesson);
        assertThat(infoLinesOf(() -> send(HttpMethod.PUT, publication, "{\"status\": \"PUBLISHED\"}"))).isEmpty();
        assertThat(infoLinesOf(() -> send(HttpMethod.DELETE, "/v1/admin/lessons/" + lesson, ""))).isEmpty();
        assertThat(infoLinesOf(() -> send(HttpMethod.PUT, "/v1/admin/lessons/" + lesson,
                "{\"title\": \"APIs\", \"slug\": \"apis\"}"))).isEmpty();
    }

    /** One line, whose values are exactly the ids given. */
    private static void assertNamesOnly(List<ILoggingEvent> lines, Object... ids) {
        assertThat(lines).singleElement()
                .satisfies(line -> assertThat(line.getArgumentArray()).containsExactlyInAnyOrder(ids));
    }

    private List<ILoggingEvent> infoLinesOf(Supplier<MvcTestResult> request) {
        return logged(request).infoLines();
    }

    /** The answer to the request, with the INFO lines the application logged while answering it. */
    private Logged logged(Supplier<MvcTestResult> request) {
        appender.list.clear();
        MvcTestResult response = request.get();
        return new Logged(response, appender.list.stream().filter(line -> line.getLevel() == Level.INFO).toList());
    }

    private record Logged(MvcTestResult response, List<ILoggingEvent> infoLines) {
    }

    @Test
    void logsNoPersonalDataOfTheAdminNorTheirToken(CapturedOutput output) {
        String email = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        String name = "Ana " + UUID.randomUUID();
        accounts.createAdmin(email, name, PASSWORD);
        token = new AdminApi(mvc).sessionToken(email, PASSWORD);
        String course = idOf(send(HttpMethod.POST, "/v1/admin/courses", """
                {"slug": "curso-%s", "title": "Backend"}""".formatted(UUID.randomUUID())));

        String first = idOf(send(HttpMethod.POST, "/v1/admin/courses/%s/modules".formatted(course), """
                {"title": "Fundamentos"}"""));
        String second = idOf(send(HttpMethod.POST, "/v1/admin/courses/%s/modules".formatted(course), """
                {"title": "Rotas"}"""));
        send(HttpMethod.PUT, "/v1/admin/modules/" + first, """
                {"title": "Fundamentos de APIs"}""");
        String lesson = idOf(send(HttpMethod.POST, "/v1/admin/modules/%s/lessons".formatted(first), """
                {"title": "O que é uma API", "slug": "o-que-e-uma-api"}"""));
        send(HttpMethod.POST, "/v1/admin/modules/%s/lessons".formatted(second), """
                {"title": "O que é uma API", "slug": "o-que-e-uma-api"}""");
        send(HttpMethod.PUT, "/v1/admin/lessons/" + lesson, """
                {"title": "O que é uma API REST", "slug": "o-que-e-uma-api-rest"}""");
        send(HttpMethod.PUT, "/v1/admin/courses/%s/outline".formatted(course), "[]");
        send(HttpMethod.PUT, "/v1/admin/courses/%s/outline".formatted(course), """
                [{"moduleId": %s, "lessonIds": [%s]}, {"moduleId": %s, "lessonIds": []}]"""
                .formatted(second, lesson, first));
        send(HttpMethod.DELETE, "/v1/admin/modules/" + second, "");
        send(HttpMethod.DELETE, "/v1/admin/lessons/" + lesson, "");
        send(HttpMethod.DELETE, "/v1/admin/modules/" + second, "");
        send(HttpMethod.DELETE, "/v1/admin/modules/" + first, "");

        assertThat(output.getAll()).isNotBlank()
                .doesNotContainIgnoringCase(email)
                .doesNotContain(name, token);
    }

    private MvcTestResult send(HttpMethod method, String path, String body) {
        return mvc.perform(MockMvcRequestBuilders.request(method, path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String idOf(MvcTestResult result) {
        return JsonPath.read(body(result), "$.id").toString();
    }
}
