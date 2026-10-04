package com.devlabs.aulaflix.exception;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.util.StringUtils;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.InputCoercionException;
import tools.jackson.databind.exc.InvalidFormatException;

/**
 * The Problems module: every refusal, in every environment, is a ProblemDetail whose {@code type} is
 * {@code https://aulaflix.com.br/problems/<name>}. The security filters hand their refusals here too.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final String TYPE_PREFIX = "https://aulaflix.com.br/problems/";

    private static final List<String> FIXING_ORDER =
            List.of("required", "too-short", "too-long", "invalid-email", "invalid-format", "out-of-range");

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    private final Clock clock;

    public ProblemHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Object> invalidRequest(InvalidRequestException refusal, HttpServletRequest request) {
        return invalidRequest(refusal.violations(), request);
    }

    @ExceptionHandler(QueryParametersNotAllowedException.class)
    ResponseEntity<Object> queryParametersNotAllowed(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                "This endpoint takes no query parameters."), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<Object> invalidCredentials(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-credentials", "Invalid credentials",
                "The email or the password is wrong."), request);
    }

    @ExceptionHandler(SignInBlockedException.class)
    ResponseEntity<Object> signInBlocked(SignInBlockedException refusal, HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.TOO_MANY_REQUESTS, "sign-in-blocked", "Sign-in blocked",
                        "Too many failed sign-ins for this email. Try again later."),
                request, retryAfter(refusal.retryAfter()), Map.of());
    }

    @ExceptionHandler(CourseNotFoundException.class)
    ResponseEntity<Object> courseNotFound(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.NOT_FOUND, "course-not-found", "Course not found",
                "The Course does not exist."), request);
    }

    @ExceptionHandler(ModuleNotFoundException.class)
    ResponseEntity<Object> moduleNotFound(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.NOT_FOUND, "module-not-found", "Module not found",
                "No Module has this id."), request);
    }

    @ExceptionHandler(LessonNotFoundException.class)
    ResponseEntity<Object> lessonNotFound(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.NOT_FOUND, "lesson-not-found", "Lesson not found",
                "No Lesson has this id."), request);
    }

    @ExceptionHandler(SlugTakenException.class)
    ResponseEntity<Object> slugTaken(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "slug-taken", "Slug taken",
                "Another Course already has this slug."), request);
    }

    @ExceptionHandler(SlugFrozenException.class)
    ResponseEntity<Object> slugFrozen(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "slug-frozen", "Slug frozen",
                "The slug changes only while the Course is a Draft."), request);
    }

    @ExceptionHandler(CourseNotDraftException.class)
    ResponseEntity<Object> courseNotDraft(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "course-not-draft", "Course not a Draft",
                "Only a Draft Course can be deleted."), request);
    }

    @ExceptionHandler(CourseCannotMoveBackException.class)
    ResponseEntity<Object> courseCannotMoveBack(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "course-cannot-move-back", "Course cannot move back",
                "A Course moves forward only: from Draft to Coming soon to On sale."), request);
    }

    @ExceptionHandler(CourseRequirementsUnmetException.class)
    ResponseEntity<Object> courseRequirementsUnmet(CourseRequirementsUnmetException refusal,
                                                   HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "course-requirements-unmet", "Course requirements unmet",
                        "A Course must have every field its state needs; missing lists those it lacks."),
                request, new HttpHeaders(), Map.of("missing", refusal.missing()));
    }

    @ExceptionHandler(ModuleNotEmptyException.class)
    ResponseEntity<Object> moduleNotEmpty(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "module-not-empty", "Module not empty",
                "Only a Module without Lessons can be deleted."), request);
    }

    @ExceptionHandler(OutlineMismatchException.class)
    ResponseEntity<Object> outlineMismatch(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "outline-mismatch", "Outline mismatch",
                "The outline must name every current Module and Lesson of the Course, each once."), request);
    }

    @ExceptionHandler(LessonSlugTakenException.class)
    ResponseEntity<Object> lessonSlugTaken(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "lesson-slug-taken", "Lesson slug taken",
                "Another Lesson of this Course already has this slug."), request);
    }

    @ExceptionHandler(PriceNotDivisibleByInstallmentsException.class)
    ResponseEntity<Object> priceNotDivisibleByInstallments(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "price-not-divisible-by-installments",
                "Price not divisible by installments",
                "priceCents must be divisible by maxInstallments, so that every installment is exact."), request);
    }

    /** Raised by the session token filter, and by the chain for a request that needs a session and has none. */
    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> unauthenticated(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return refuse(new Refusal(HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthenticated",
                "This needs a valid session token, sent as Authorization: Bearer."), request, headers, Map.of());
    }

    /** Not a 401, which the BFF would read as an ended session. */
    @ExceptionHandler(InvalidBffKeyException.class)
    ResponseEntity<Object> invalidBffKey(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.FORBIDDEN, "invalid-bff-key", "Invalid BFF key",
                "Only the AulaFlix web server calls this API, with its AulaFlix-BFF-Key."), request);
    }

    @ExceptionHandler(InvalidClientIpException.class)
    ResponseEntity<Object> invalidClientIp(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-client-ip", "Invalid client IP",
                "The BFF sends the browser's IP address as AulaFlix-Client-IP."), request);
    }

    /** A valid session of the wrong role, refused by the chain or by {@code @PreAuthorize}. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> forbidden(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                "This session's role may not do this."), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception failure, HttpServletRequest request) {
        log.error("Unexpected failure on {} {}", request.getMethod(), request.getRequestURI(), failure);
        ProblemDetail problem = problem(new Refusal(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Internal error", "The request could not be completed."));
        return ResponseEntity.internalServerError().body(problem);
    }

    /**
     * Bean validation on a request body; each violation's message is the field's code. A field that breaks several
     * constraints, like a blank slug that also misses the slug's pattern, gets only the code to fix first.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException failure,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return invalidRequest(firstToFixByField(failure.getBindingResult().getFieldErrors().stream()
                        .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))),
                servletRequest(request));
    }

    /**
     * Method validation, which Spring MVC runs in place of the binder's once a parameter has constraints of its own,
     * as a request body that is a list has on its elements. A list element is named the way Jackson names it, from its
     * index: {@code [1].moduleId}, or {@code [1]} for a null element. Any other parameter is named as a body field, or
     * after the parameter.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException failure,
                                                                            HttpHeaders headers,
                                                                            HttpStatusCode status,
                                                                            WebRequest request) {
        return invalidRequest(firstToFixByField(failure.getParameterValidationResults().stream()
                .flatMap(ProblemHandler::fieldViolations)), servletRequest(request));
    }

    private static Stream<FieldViolation> fieldViolations(ParameterValidationResult result) {
        Integer index = result.getContainerIndex();
        if (result instanceof ParameterErrors errors) {
            return errors.getFieldErrors().stream().map(error -> new FieldViolation(
                    index == null ? error.getField() : "[%d].%s".formatted(index, error.getField()),
                    error.getDefaultMessage()));
        }
        String field = index == null ? result.getMethodParameter().getParameterName() : "[%d]".formatted(index);
        return result.getResolvableErrors().stream().map(error -> new FieldViolation(field, error.getDefaultMessage()));
    }

    private static List<FieldViolation> firstToFixByField(Stream<FieldViolation> violations) {
        return List.copyOf(violations.collect(Collectors.toMap(FieldViolation::field, Function.identity(),
                BinaryOperator.minBy(Comparator.comparingInt(ProblemHandler::fixingOrder)))).values());
    }

    /**
     * JSON that is not the shape the endpoint expects answers without {@code errors}. A field whose value has the
     * right JSON type but cannot be read, like an unknown code, a fractional number of cents or a number too large
     * for the field, is named instead.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException failure,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        return unreadableField(failure.getCause())
                .map(violation -> invalidRequest(List.of(violation), servletRequest(request)))
                .orElseGet(() -> refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                        "The body is not the JSON this endpoint expects."), servletRequest(request)));
    }

    private static Optional<FieldViolation> unreadableField(Throwable cause) {
        if (!(cause instanceof JacksonException failure) || failure.getPath().isEmpty()) {
            return Optional.empty();
        }
        return switch (failure) {
            case InvalidFormatException invalid -> Optional.of(new FieldViolation(fieldOf(invalid), "invalid-format"));
            case InputCoercionException tooLarge -> Optional.of(new FieldViolation(fieldOf(tooLarge), "out-of-range"));
            default -> Optional.empty();
        };
    }

    /**
     * Every refusal Spring MVC makes on its own (an unknown path, a wrong method or media type, …) passes through
     * here with its type unset, which RFC 9457 reads as {@code about:blank}. It gets a type named after its status,
     * and the same envelope as the API's own refusals.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
                                                          WebRequest request) {
        if (body instanceof ProblemDetail problem && problem.getType() == null) {
            String name = HttpStatus.valueOf(statusCode.value()).name().toLowerCase(Locale.ROOT).replace('_', '-');
            HttpServletRequest servletRequest = servletRequest(request);
            log.warn("Refused {} {}: {}", servletRequest.getMethod(), servletRequest.getRequestURI(), name);
            dress(problem, name, StringUtils.capitalize(name.replace('-', ' ')));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /** The fields in a fixed order, since the web shows only the first error. */
    private ResponseEntity<Object> invalidRequest(List<FieldViolation> violations, HttpServletRequest request) {
        List<FieldViolation> errors = violations.stream().sorted(Comparator.comparing(FieldViolation::field)).toList();
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                "One or more fields are invalid."), request, new HttpHeaders(), Map.of("errors", errors));
    }

    private ResponseEntity<Object> refuse(Refusal refusal, HttpServletRequest request) {
        return refuse(refusal, request, new HttpHeaders(), Map.of());
    }

    private ResponseEntity<Object> refuse(Refusal refusal, HttpServletRequest request, HttpHeaders headers,
                                          Map<String, Object> extensions) {
        log.warn("Refused {} {}: {}", request.getMethod(), request.getRequestURI(), refusal.name());
        ProblemDetail problem = problem(refusal);
        extensions.forEach(problem::setProperty);
        return ResponseEntity.status(refusal.status()).headers(headers).body(problem);
    }

    private ProblemDetail problem(Refusal refusal) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(refusal.status(), refusal.detail());
        dress(problem, refusal.name(), refusal.title());
        return problem;
    }

    /** The type, title and time every problem carries; Spring MVC sets the request path as its instance. */
    private void dress(ProblemDetail problem, String name, String title) {
        problem.setType(URI.create(TYPE_PREFIX + name));
        problem.setTitle(title);
        problem.setProperty("timestamp", clock.instant().toString());
    }

    /** Named the way bean validation names a field: {@code faq[0].question}. */
    private static String fieldOf(JacksonException failure) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference reference : failure.getPath()) {
            if (reference.getPropertyName() == null) {
                field.append('[').append(reference.getIndex()).append(']');
            } else {
                field.append(field.isEmpty() ? "" : ".").append(reference.getPropertyName());
            }
        }
        return field.toString();
    }

    /** A missing value has no length to check, and a value of the wrong length is fixed before its format. */
    private static int fixingOrder(FieldViolation violation) {
        int order = FIXING_ORDER.indexOf(violation.code());
        return order < 0 ? FIXING_ORDER.size() : order;
    }

    /** Whole seconds, rounded up, so a client that waits that long is never refused again for the same reason. */
    private static HttpHeaders retryAfter(Duration wait) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, Long.toString(wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0)));
        return headers;
    }

    private static HttpServletRequest servletRequest(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }

    /** One of the API's problem types: its status, its {@code <name>}, and the title and detail it always carries. */
    private record Refusal(HttpStatus status, String name, String title, String detail) {
    }
}
