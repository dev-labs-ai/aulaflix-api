package com.devlabs.aulaflix;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * One Student's "Meus cursos", Lesson marks and Lesson visits through the HTTP contract, the way the BFF calls them for one browser
 * with the Student's session. Ids go into the path as given, so a test can send an id of any shape.
 */
public final class LearningApi {

    public static final String LESSON_VISITS = "/v1/account/lesson-visits";

    private final BffApi bff;
    private final String bearer;

    public LearningApi(BffApi bff, String studentToken) {
        this.bff = bff;
        this.bearer = "Bearer " + studentToken;
    }

    public MvcTestResult enrollments() {
        return bff.get("/v1/account/enrollments").header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    public MvcTestResult enrollment(Object courseId) {
        return bff.get("/v1/account/enrollments/" + courseId).header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    public MvcTestResult complete(Object lessonId) {
        return bff.put(completedLesson(lessonId)).header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    public MvcTestResult uncomplete(Object lessonId) {
        return bff.delete(completedLesson(lessonId)).header(HttpHeaders.AUTHORIZATION, bearer).exchange();
    }

    /** Marks each Lesson as completed, failing the test unless every mark is taken. */
    public void completed(long... lessonIds) {
        for (long lessonId : lessonIds) {
            assertThat(complete(lessonId)).hasStatus(HttpStatus.NO_CONTENT);
        }
    }

    /** Opens the Lesson's page, which records the visit; the id goes into the body as given, a string quoted. */
    public MvcTestResult visit(Object lessonId) {
        String value = lessonId instanceof String ? "\"" + lessonId + "\"" : String.valueOf(lessonId);
        return visitWith("{\"lessonId\": " + value + "}");
    }

    public MvcTestResult visitWith(String body) {
        return bff.post(LESSON_VISITS).header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    /** Opens each Lesson's page in turn, failing the test unless every visit is taken. */
    public void visited(long... lessonIds) {
        for (long lessonId : lessonIds) {
            assertThat(visit(lessonId)).hasStatus(HttpStatus.NO_CONTENT);
        }
    }

    public static String completedLesson(Object lessonId) {
        return "/v1/account/completed-lessons/" + lessonId;
    }
}
