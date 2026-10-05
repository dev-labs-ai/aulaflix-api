package com.devlabs.aulaflix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;
import tools.jackson.databind.json.JsonMapper;

/**
 * Grants, ends and reads Enrollments through the Admin endpoints, the way the Admin does with curl. The fields go out as
 * JSON, escaped, whatever they hold; a field given as null is left out of the body.
 */
public final class AdminEnrollments {

    private final MockMvcTester mvc;
    private final String bearer;

    public AdminEnrollments(MockMvcTester mvc, String adminToken) {
        this.mvc = mvc;
        this.bearer = "Bearer " + adminToken;
    }

    public MvcTestResult grant(String email, long courseId, String note) {
        return grant(fields("email", email, "courseId", courseId, "note", note));
    }

    /** A grant whose body holds exactly these fields. */
    public MvcTestResult grant(Map<String, Object> body) {
        return mvc.post().uri("/v1/admin/enrollments")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.shared().writeValueAsString(body))
                .exchange();
    }

    /** The new Enrollment's id, failing the test unless the grant succeeds. */
    public long granted(String email, long courseId) {
        MvcTestResult grant = grant(email, courseId, "Cortesia para quem revisou o curso.");
        assertThat(grant).hasStatus(HttpStatus.CREATED);
        return ((Number) JsonPath.read(AdminApi.body(grant), "$.id")).longValue();
    }

    public MvcTestResult changeStatus(String enrollmentId, String status, String note) {
        return mvc.put().uri("/v1/admin/enrollments/%s/status".formatted(enrollmentId))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonMapper.shared().writeValueAsString(fields("status", status, "note", note)))
                .exchange();
    }

    public MvcTestResult end(long enrollmentId, String note) {
        return changeStatus(Long.toString(enrollmentId), "ENDED", note);
    }

    /** Ends the Enrollment, failing the test unless the Admin endpoint takes it. */
    public void ended(long enrollmentId) {
        assertThat(end(enrollmentId, "Acesso de cortesia encerrado.")).hasStatusOk();
    }

    public MvcTestResult get(String enrollmentId) {
        return mvc.get().uri("/v1/admin/enrollments/" + enrollmentId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange();
    }

    /** The list, with the query string as given: {@code "email=…&active=true"}, or empty. */
    public MvcTestResult list(String query) {
        return mvc.get().uri("/v1/admin/enrollments" + (query.isEmpty() ? "" : "?" + query))
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange();
    }

    /** Name, value, name, value, …, without the names whose value is null. */
    public static Map<String, Object> fields(Object... namesAndValues) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (namesAndValues[i + 1] != null) {
                fields.put((String) namesAndValues[i], namesAndValues[i + 1]);
            }
        }
        return fields;
    }
}
