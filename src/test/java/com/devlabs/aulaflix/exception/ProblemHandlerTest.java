package com.devlabs.aulaflix.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Bean validation reports a field's violations in no fixed order, so the HTTP tests cannot choose which one comes
 * first. This hands the handler a field whose violations arrive worst first. It also hands method validation the
 * parameters no endpoint has yet: those that are not a list's elements.
 */
class ProblemHandlerTest {

    private final ProblemHandler handler =
            new ProblemHandler(Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC));

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "out-of-range, invalid-format, invalid-email, too-long, too-short, required | required",
            "out-of-range, invalid-format, invalid-email, too-long, too-short           | too-short",
            "out-of-range, invalid-format, invalid-email, too-long                      | too-long",
            "out-of-range, invalid-format, invalid-email                                | invalid-email",
            "out-of-range, invalid-format                                               | invalid-format",
            "a-code-of-the-future, out-of-range                                         | out-of-range"})
    void givesAFieldThatBreaksSeveralConstraintsOnlyTheCodeToFixFirst(String codesAsReported, String codeToFixFirst)
            throws NoSuchMethodException {
        BeanPropertyBindingResult result = new BeanPropertyBindingResult(new Object(), "document");
        for (String code : codesAsReported.split(",")) {
            result.addError(new FieldError("document", "slug", code.strip()));
        }
        result.addError(new FieldError("document", "title", "too-long"));
        MethodArgumentNotValidException failure = new MethodArgumentNotValidException(
                new MethodParameter(Object.class.getMethod("equals", Object.class), 0), result);

        ResponseEntity<Object> response = handler.handleMethodArgumentNotValid(failure, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, new ServletWebRequest(new MockHttpServletRequest("PUT", "/v1/admin/courses/1")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ProblemDetail) response.getBody()).getProperties()).containsEntry("errors", List.of(
                new FieldViolation("slug", codeToFixFirst),
                new FieldViolation("title", "too-long")));
    }

    @Test
    void namesAParameterOutsideAListAsABodyFieldOrAfterTheParameter() throws NoSuchMethodException {
        Method method = Endpoint.class.getDeclaredMethod("update", String.class, Object.class);
        MethodParameter id = new MethodParameter(method, 0);
        id.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        BeanPropertyBindingResult bodyErrors = new BeanPropertyBindingResult(new Object(), "body");
        bodyErrors.addError(new FieldError("body", "title", "too-long"));
        HandlerMethodValidationException failure = new HandlerMethodValidationException(MethodValidationResult.create(
                new Endpoint(), method, List.of(
                        new ParameterValidationResult(id, "x", List.of(new DefaultMessageSourceResolvable(
                                null, "invalid-format")), null, null, null, (error, type) -> null),
                        new ParameterErrors(new MethodParameter(method, 1), new Object(), bodyErrors, null, null,
                                null))));

        ResponseEntity<Object> response = handler.handleHandlerMethodValidationException(failure, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, new ServletWebRequest(new MockHttpServletRequest("PUT", "/v1/admin/x")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ProblemDetail) response.getBody()).getProperties()).containsEntry("errors", List.of(
                new FieldViolation("courseId", "invalid-format"),
                new FieldViolation("title", "too-long")));
    }

    private static final class Endpoint {

        @SuppressWarnings("unused")
        void update(String courseId, Object body) {
        }
    }
}
