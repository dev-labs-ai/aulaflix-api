package com.devlabs.aulaflix.controller;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AdminEnrollment;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.EnrollmentSearch;
import com.devlabs.aulaflix.dto.EnrollmentStatusChange;
import com.devlabs.aulaflix.dto.ManualEnrollmentRequest;
import com.devlabs.aulaflix.dto.PageResponse;
import com.devlabs.aulaflix.exception.QueryParametersNotAllowedException;
import com.devlabs.aulaflix.service.EnrollmentService;

/** Every refusal is a ProblemDetail; an id of any shape answers like an unknown one. */
@RestController
@RequestMapping("/v1/admin/enrollments")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@Tag(name = "Admin enrollments", description = "Granting and ending Enrollments by hand, and listing every one")
public class AdminEnrollmentController {

    /** The filters, then the page: the order is fixed, so {@code sort} is refused like any other. */
    private static final List<String> LIST_PARAMETERS = List.of("email", "courseId", "active", "page", "size");

    private final EnrollmentService enrollments;

    public AdminEnrollmentController(EnrollmentService enrollments) {
        this.enrollments = enrollments;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Grant an Enrollment by hand", description = """
            Gives a Student every published Lesson of a Course, for example to restore access after a chargeback won \
            in the Asaas UI, or as a courtesy. The note is required: it is the only record of why. A Coming soon \
            Course takes Enrollments too, whose Lessons open at the launch. No email is sent: tell the Student.""")
    @ApiResponse(responseCode = "201", description = "Granted; `Location` is the new Enrollment's address",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminEnrollment.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "409", description = """
            `student-account-required`: no Student Account has the email, which may be an Admin's: the person signs \
            up first; `course-not-enrollable`: no Course has the id, or it is a Draft; or `already-enrolled`: the \
            Student already has an active Enrollment in the Course""")
    public ResponseEntity<AdminEnrollment> grant(@AuthenticationPrincipal AuthenticatedAccount admin,
                                                 @Valid @RequestBody ManualEnrollmentRequest request) {
        AdminEnrollment enrollment = enrollments.grantManually(admin.accountId(), request);
        return ResponseEntity.created(URI.create("/v1/admin/enrollments/" + enrollment.id())).body(enrollment);
    }

    /** The answer's type is generic, so its media type is declared here for springdoc to describe it. */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List every Enrollment", description = """
            Active and ended, newest first, each as its own read shows it: who granted it and why, and how it \
            ended. Paginated, 20 to a page by default and at most 100. Each filter is optional, and they combine.""")
    @Parameter(name = "email", in = ParameterIn.QUERY,
            description = "The Student's email, matched trimmed and lower-cased")
    @Parameter(name = "courseId", in = ParameterIn.QUERY, description = "The Course's id",
            schema = @Schema(type = "integer", format = "int64"))
    @Parameter(name = "active", in = ParameterIn.QUERY,
            description = "`true` for active Enrollments only, `false` for ended ones only",
            schema = @Schema(type = "boolean"))
    @Parameter(name = "page", in = ParameterIn.QUERY, description = "The page, from 0",
            schema = @Schema(type = "integer", defaultValue = "0", minimum = "0"))
    @Parameter(name = "size", in = ParameterIn.QUERY, description = "How many to a page",
            schema = @Schema(type = "integer", defaultValue = "20", minimum = "1", maximum = "100"))
    @ApiResponse(responseCode = "200", description = "One page of Enrollments")
    @ApiResponse(responseCode = "400", description = """
            `invalid-request`: `active` is neither `true` nor `false`, with its code in `errors`; or the request \
            carries any other query parameter""")
    public PageResponse<AdminEnrollment> list(HttpServletRequest request,
                                              @RequestParam(required = false) String email,
                                              @RequestParam(required = false) String courseId,
                                              @RequestParam(required = false) String active,
                                              @Parameter(hidden = true) @PageableDefault(size = 20) Pageable page) {
        if (!LIST_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
            throw new QueryParametersNotAllowedException(LIST_PARAMETERS);
        }
        EnrollmentSearch search = new EnrollmentSearch(Optional.ofNullable(email), QueryFilters.id(courseId),
                QueryFilters.trueOrFalse("active", active));
        if (QueryFilters.matchesNothing(courseId)) {
            return PageResponse.empty(page.getPageNumber(), page.getPageSize());
        }
        return enrollments.list(search, page);
    }

    @GetMapping("/{enrollmentId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Read an Enrollment", description = "Active or ended, with its origin and its end.")
    @ApiResponse(responseCode = "200", description = "The Enrollment",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminEnrollment.class)))
    @ApiResponse(responseCode = "404", description = "`enrollment-not-found`")
    public AdminEnrollment get(@PathVariable String enrollmentId) {
        return enrollments.get(enrollmentId);
    }

    @PutMapping("/{enrollmentId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "End a manual Enrollment", description = """
            Ends an Enrollment granted by hand, with a note saying why; the Student loses every Lesson but the Free \
            one. The ending is final: the Enrollment stays in the list, ended, and access comes back only through a \
            new grant. `ENDED` is the only status taken; ending an ended Enrollment changes nothing, so a retry is \
            harmless.""")
    @ApiResponse(responseCode = "200", description = "The Enrollment as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminEnrollment.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "404", description = "`enrollment-not-found`")
    @ApiResponse(responseCode = "409", description = """
            `paid-enrollment`: an Order granted it, and it ends only with a Refund of that Order""")
    public AdminEnrollment changeStatus(@AuthenticationPrincipal AuthenticatedAccount admin,
                                        @PathVariable String enrollmentId,
                                        @Valid @RequestBody EnrollmentStatusChange change) {
        return enrollments.changeStatus(admin.accountId(), enrollmentId, change);
    }
}
