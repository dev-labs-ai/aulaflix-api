package com.devlabs.aulaflix.controller;

import static com.devlabs.aulaflix.AdminApi.body;
import static com.devlabs.aulaflix.AdminCourses.newSlug;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

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
import com.devlabs.aulaflix.AdminEnrollments;
import com.devlabs.aulaflix.BffApi;
import com.devlabs.aulaflix.IntegrationTest;
import com.devlabs.aulaflix.StoredVideos;
import com.devlabs.aulaflix.StudentApi;
import com.devlabs.aulaflix.service.AccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * Every Admin mutation of an Enrollment logs one INFO line, so the records' own audit has a trail beside it. Logs must
 * not become a leak: whatever the wording, no line carries the Student's or the Admin's email or name, a note, or a
 * token.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminEnrollmentLogsTest extends IntegrationTest {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private AccountService accounts;

    @Autowired
    private StoredVideos storedVideos;

    private String adminEmail;

    private String adminName;

    private String adminToken;

    private AdminCourses courses;

    private AdminEnrollments enrollments;

    @BeforeEach
    void signInAnAdmin() {
        adminEmail = "admin-" + UUID.randomUUID() + "@aulaflix.com.br";
        adminName = "Ana " + UUID.randomUUID();
        accounts.createAdmin(adminEmail, adminName, PASSWORD);
        adminToken = new AdminApi(mvc).sessionToken(adminEmail, PASSWORD);
        courses = new AdminCourses(mvc, adminToken, storedVideos);
        enrollments = new AdminEnrollments(mvc, adminToken);
    }

    @Test
    void logsOneInfoLineForTheGrantAndOneForTheEnd(CapturedOutput output) {
        long course = courses.onSale(newSlug());
        String email = StudentApi.newEmail();
        new StudentApi(mvc).signedUp(email, PASSWORD);

        Logged grant = logged(output,
                () -> enrollments.grant(email, course, "Cortesia para quem revisou o curso."));
        long id = ((Number) JsonPath.read(body(grant.result()), "$.id")).longValue();
        Logged end = logged(output, () -> enrollments.end(id, "Cortesia encerrada."));
        Logged retriedEnd = logged(output, () -> enrollments.end(id, "Cortesia encerrada."));

        assertThat(grant.infoLines()).singleElement().asString().contains("granted", "Enrollment " + id);
        assertThat(end.infoLines()).singleElement().asString().contains("ended", "Enrollment " + id);
        assertThat(retriedEnd.infoLines()).isEmpty();
    }

    @Test
    void logsNoPersonalDataNotesNorTokens(CapturedOutput output) {
        long course = courses.onSale(newSlug());
        long lesson = courses.addPublishedLesson(courses.addModule(course, "Rotas e respostas"), "Rotas no Express",
                "rotas-no-express", "five-seconds.mp4");
        String studentName = "Bia " + UUID.randomUUID();
        String email = StudentApi.newEmail();
        BffApi bff = new BffApi(mvc);
        String studentToken = AdminApi.tokenOf(new StudentApi(bff).signUp(studentName, email, PASSWORD));
        String grantNote = "Chargeback ganho " + UUID.randomUUID();
        String endNote = "Cortesia encerrada " + UUID.randomUUID();
        String unknownEmail = StudentApi.newEmail();

        enrollments.grant(unknownEmail, course, grantNote);
        enrollments.grant(adminEmail, course, grantNote);
        long id = ((Number) JsonPath.read(body(enrollments.grant(email, course, grantNote)), "$.id")).longValue();
        enrollments.grant(email, course, grantNote);
        enrollments.list("email=" + email);
        enrollments.end(id, endNote);
        enrollments.changeStatus(Long.toString(id), "ACTIVE", endNote);
        bff.get("/v1/lessons/%d/playback".formatted(lesson))
                .header("Authorization", "Bearer " + studentToken).exchange();

        assertThat(output.getAll()).isNotBlank()
                .doesNotContainIgnoringCase(email)
                .doesNotContainIgnoringCase(unknownEmail)
                .doesNotContainIgnoringCase(adminEmail)
                .doesNotContain(studentName, adminName, grantNote, endNote, studentToken, adminToken);
    }

    /** Runs the request, which must succeed, with the INFO lines written meanwhile. */
    private static Logged logged(CapturedOutput output, Supplier<MvcTestResult> request) {
        int before = output.getAll().length();
        MvcTestResult result = request.get();
        assertThat(result.getResponse().getStatus()).isIn(HttpStatus.OK.value(), HttpStatus.CREATED.value());
        return new Logged(result,
                output.getAll().substring(before).lines().filter(line -> line.contains(" INFO ")).toList());
    }

    private record Logged(MvcTestResult result, List<String> infoLines) {
    }
}
