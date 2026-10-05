package com.devlabs.aulaflix;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * One Student's "Meus cursos" and Lesson marks through the HTTP contract, the way the BFF calls them for one browser
 * with the Student's session. Ids go into the path as given, so a test can send an id of any shape.
 */
public final class LearningApi {

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

    public static String completedLesson(Object lessonId) {
        return "/v1/account/completed-lessons/" + lessonId;
    }
}
