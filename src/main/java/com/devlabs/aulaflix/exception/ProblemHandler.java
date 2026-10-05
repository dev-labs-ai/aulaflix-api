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
    ResponseEntity<Object> queryParametersNotAllowed(QueryParametersNotAllowedException refusal,
                                                     HttpServletRequest request) {
        String detail = refusal.allowed().isEmpty() ? "This endpoint takes no query parameters."
                : "This endpoint takes only these query parameters: %s.".formatted(String.join(", ", refusal.allowed()));
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request", detail), request);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<Object> invalidCredentials(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-credentials", "Invalid credentials",
                "The email or the password is wrong."), request);
    }

    @ExceptionHandler(EmailTakenException.class)
    ResponseEntity<Object> emailTaken(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "email-taken", "Email taken",
                "An Account with this email already exists: sign in with its password."), request);
    }

    @ExceptionHandler(InvalidConfirmationLinkException.class)
    ResponseEntity<Object> invalidConfirmationLink(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-confirmation-link", "Invalid confirmation link",
                "The link is unknown, expired, or replaced by a newer one: ask for a new one."), request);
    }

    @ExceptionHandler(InvalidCodeException.class)
    ResponseEntity<Object> invalidCode(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-code", "Invalid code",
                "The code is wrong or expired: check it, or ask for a new one."), request);
    }

    @ExceptionHandler(EmailAlreadyConfirmedException.class)
    ResponseEntity<Object> emailAlreadyConfirmed(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "email-already-confirmed", "Email already confirmed",
                "The Account's email is already confirmed: there is no link to send."), request);
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

    @ExceptionHandler(FreeLessonIneligibleException.class)
    ResponseEntity<Object> freeLessonIneligible(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "free-lesson-ineligible", "Free lesson ineligible",
                "freeLessonId must name a published Lesson of this Course."), request);
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

    @ExceptionHandler(LessonSlugFrozenException.class)
    ResponseEntity<Object> lessonSlugFrozen(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "lesson-slug-frozen", "Lesson slug frozen",
                "The slug of a published Lesson never changes."), request);
    }

    @ExceptionHandler(LessonPublishedException.class)
    ResponseEntity<Object> lessonPublished(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "lesson-published", "Lesson published",
                "A published Lesson is never deleted."), request);
    }

    @ExceptionHandler(VideoRequiredException.class)
    ResponseEntity<Object> videoRequired(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "video-required", "Video required",
                "A Lesson is published only with a video: upload one and link it first."), request);
    }

    @ExceptionHandler(VideoNotFoundException.class)
    ResponseEntity<Object> videoNotFound(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "video-not-found", "Video not found",
                "No video was uploaded under this key for this Lesson: request an upload URL, upload, then link."),
                request);
    }

    /** The Admin is told only that the file is not an MP4; the line says why, for whoever looks into it. */
    @ExceptionHandler(VideoNotMp4Exception.class)
    ResponseEntity<Object> videoNotMp4(VideoNotMp4Exception refusal, HttpServletRequest request) {
        log.warn("Refused {} {}: video-not-mp4; {}", request.getMethod(), request.getRequestURI(), refusal.reason());
        return respond(new Refusal(HttpStatus.CONFLICT, "video-not-mp4", "Video not MP4",
                "The file is not an MP4: encode it with ffmpeg as a faststart H.264/AAC MP4."), new HttpHeaders(),
                Map.of());
    }

    @ExceptionHandler(VideoNotFaststartException.class)
    ResponseEntity<Object> videoNotFaststart(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "video-not-faststart", "Video not faststart",
                "The file's index does not come before its media: encode it again with -movflags +faststart."),
                request);
    }

    @ExceptionHandler(VideoNotH264Exception.class)
    ResponseEntity<Object> videoNotH264(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "video-not-h264", "Video not H.264",
                "The video is not H.264 (avc1 or avc3): encode it again with -c:v libx264."), request);
    }

    @ExceptionHandler(AudioNotAacException.class)
    ResponseEntity<Object> audioNotAac(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "audio-not-aac", "Audio not AAC",
                "The audio is not AAC: encode it again with -c:a aac, or without audio, with -an."), request);
    }

    @ExceptionHandler(VideoTooShortException.class)
    ResponseEntity<Object> videoTooShort(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "video-too-short", "Video too short",
                "The video lasts under a second, once rounded to the nearest second."), request);
    }

    @ExceptionHandler(VideoNotLinkedException.class)
    ResponseEntity<Object> videoNotLinked(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.NOT_FOUND, "video-not-linked", "Video not linked",
                "The Lesson has no video yet."), request);
    }

    @ExceptionHandler(PriceNotDivisibleByInstallmentsException.class)
    ResponseEntity<Object> priceNotDivisibleByInstallments(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "price-not-divisible-by-installments",
                "Price not divisible by installments",
                "priceCents must be divisible by maxInstallments, so that every installment is exact."), request);
    }

    @ExceptionHandler(StudentAccountRequiredException.class)
    ResponseEntity<Object> studentAccountRequired(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "student-account-required", "Student Account required",
                "No Student Account has this email: the person signs up first."), request);
    }

    @ExceptionHandler(CourseNotEnrollableException.class)
    ResponseEntity<Object> courseNotEnrollable(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "course-not-enrollable", "Course not enrollable",
                "Only a Coming soon or On sale Course takes Enrollments."), request);
    }

    @ExceptionHandler(AlreadyEnrolledException.class)
    ResponseEntity<Object> alreadyEnrolled(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "already-enrolled", "Already enrolled",
                "The Student already has an active Enrollment in this Course."), request);
    }

    @ExceptionHandler(EnrollmentNotFoundException.class)
    ResponseEntity<Object> enrollmentNotFound(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.NOT_FOUND, "enrollment-not-found", "Enrollment not found",
                "No Enrollment has this id."), request);
    }

    @ExceptionHandler(EnrollmentEndedException.class)
    ResponseEntity<Object> enrollmentEnded(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "enrollment-ended", "Enrollment ended",
                "An ending is final: grant a new Enrollment to give access back."), request);
    }

    @ExceptionHandler(PaidEnrollmentException.class)
    ResponseEntity<Object> paidEnrollment(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "paid-enrollment", "Paid Enrollment",
                "An Enrollment granted by an Order ends only with a Refund of that Order."), request);
    }

    @ExceptionHandler(EnrollmentRequiredException.class)
    ResponseEntity<Object> enrollmentRequired(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "enrollment-required", "Enrollment required",
                "This needs an active Enrollment in the Course."), request);
    }

    @ExceptionHandler(CourseNotForSaleException.class)
    ResponseEntity<Object> courseNotForSale(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "course-not-for-sale", "Course not for sale",
                "Only an On sale Course can be bought."), request);
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ResponseEntity<Object> orderNotFound(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.NOT_FOUND, "order-not-found", "Order not found",
                "The Student has no Order with this code."), request);
    }

    @ExceptionHandler(OrderNotPaidException.class)
    ResponseEntity<Object> orderNotPaid(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONFLICT, "order-not-paid", "Order not paid",
                "Only a paid Order is refunded: this one was never paid, or was reversed."), request);
    }

    /** The line carries Asaas's codes; the Admin reads Asaas's own descriptions in {@code reasons}. */
    @ExceptionHandler(RefundRefusedException.class)
    ResponseEntity<Object> refundRefused(RefundRefusedException refusal, HttpServletRequest request) {
        log.warn("Refused {} {}: refund-refused; {}", request.getMethod(), request.getRequestURI(),
                refusal.getMessage());
        return respond(new Refusal(HttpStatus.CONFLICT, "refund-refused", "Refund refused",
                "The payment provider refused the refund, for the reasons it gave; nothing changed."),
                new HttpHeaders(), Map.of("reasons", refusal.reasons()));
    }

    /** The line says which call failed and how, for whoever looks into it; Asaas being away is not our fault. */
    @ExceptionHandler(PaymentUnavailableException.class)
    ResponseEntity<Object> paymentUnavailable(PaymentUnavailableException refusal, HttpServletRequest request) {
        log.warn("Refused {} {}: payment-unavailable; {}", request.getMethod(), request.getRequestURI(),
                refusal.getMessage());
        return respond(new Refusal(HttpStatus.SERVICE_UNAVAILABLE, "payment-unavailable", "Payment unavailable",
                "The payment provider cannot be reached now. Try again in Retry-After seconds."),
                retryAfter(refusal.retryAfter()), Map.of());
    }

    /** At ERROR: a refusal by Asaas that a retry will not fix is a fault someone has to look into. */
    @ExceptionHandler(PaymentProviderErrorException.class)
    ResponseEntity<Object> paymentProviderError(PaymentProviderErrorException refusal, HttpServletRequest request) {
        log.error("Refused {} {}: payment-provider-error; {}", request.getMethod(), request.getRequestURI(),
                refusal.getMessage());
        return respond(new Refusal(HttpStatus.BAD_GATEWAY, "payment-provider-error", "Payment provider error",
                "The payment provider refused the payment. It has been logged."), new HttpHeaders(), Map.of());
    }

    /**
     * Raised by the session token filter, by the chain for a request that needs a session and has none, and by a
     * service once what the request asks for turns out to need one.
     */
    @ExceptionHandler({AuthenticationException.class, SessionRequiredException.class})
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

    @ExceptionHandler(InvalidWebhookTokenException.class)
    ResponseEntity<Object> invalidWebhookToken(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.FORBIDDEN, "invalid-webhook-token", "Invalid webhook token",
                "Only Asaas posts here, with its asaas-access-token."), request);
    }

    @ExceptionHandler(WebhookBodyTooLargeException.class)
    ResponseEntity<Object> webhookBodyTooLarge(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.CONTENT_TOO_LARGE, "content-too-large", "Content too large",
                "A webhook's body is at most 256 KB."), request);
    }

    @ExceptionHandler(InvalidClientIpException.class)
    ResponseEntity<Object> invalidClientIp(HttpServletRequest request) {
        return refuse(new Refusal(HttpStatus.BAD_REQUEST, "invalid-client-ip", "Invalid client IP",
                "The BFF sends the browser's IP address as AulaFlix-Client-IP."), request);
    }

    /**
     * Logged once per key and window, naming the limit and the key: a line per request would let a flood fill the log.
     */
    @ExceptionHandler(RateLimitedException.class)
    ResponseEntity<Object> rateLimited(RateLimitedException refusal, HttpServletRequest request) {
        if (refusal.firstOfItsWindow()) {
            log.warn("Refused {} {}: rate-limited; {}, until {}", request.getMethod(), request.getRequestURI(),
                    refusal.getMessage(), clock.instant().plus(refusal.retryAfter()));
        }
        return respond(new Refusal(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Rate limited",
                "Too many requests. Try again in Retry-After seconds."), retryAfter(refusal.retryAfter()), Map.of());
    }

    /**
     * Not logged: the soft limit's crossing already is, once per key and window, and the web retries at once with a
     * token, so a line per refusal would only repeat it.
     */
    @ExceptionHandler(CaptchaRequiredException.class)
    ResponseEntity<Object> captchaRequired() {
        return respond(new Refusal(HttpStatus.TOO_MANY_REQUESTS, "captcha-required", "CAPTCHA required",
                "Solve the CAPTCHA, then send the request again with its token in AulaFlix-Captcha-Token."),
                new HttpHeaders(), Map.of());
    }

    @ExceptionHandler(CaptchaUnavailableException.class)
    ResponseEntity<Object> captchaUnavailable(CaptchaUnavailableException refusal, HttpServletRequest request) {
        log.warn("Refused {} {}: captcha-unavailable; {}", request.getMethod(), request.getRequestURI(),
                refusal.getMessage());
        return respond(new Refusal(HttpStatus.SERVICE_UNAVAILABLE, "captcha-unavailable", "CAPTCHA unavailable",
                "The CAPTCHA cannot be verified now. Try again in Retry-After seconds."),
                retryAfter(refusal.retryAfter()), Map.of());
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
        return respond(refusal, headers, extensions);
    }

    private ResponseEntity<Object> respond(Refusal refusal, HttpHeaders headers, Map<String, Object> extensions) {
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
