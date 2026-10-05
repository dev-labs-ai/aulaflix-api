package com.devlabs.aulaflix.controller;

import java.net.URI;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AdminCourse;
import com.devlabs.aulaflix.dto.AdminCourseList;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.CourseDocument;
import com.devlabs.aulaflix.dto.CourseStatusChange;
import com.devlabs.aulaflix.dto.NewCourseRequest;
import com.devlabs.aulaflix.service.CourseService;

/** Every refusal is a ProblemDetail; an id of any shape answers like an unknown one. */
@RestController
@RequestMapping("/v1/admin/courses")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@Tag(name = "Admin courses", description = "Authoring the catalog's Courses, in every state")
public class AdminCourseController {

    private final CourseService courses;

    public AdminCourseController(CourseService courses) {
        this.courses = courses;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a Draft Course", description = "Only Admins see a Draft.")
    @ApiResponse(responseCode = "201", description = "Created; `Location` is the new Draft's address",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminCourse.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "409", description = "`slug-taken`")
    public ResponseEntity<AdminCourse> create(@AuthenticationPrincipal AuthenticatedAccount admin,
                                              @Valid @RequestBody NewCourseRequest request) {
        AdminCourse course = courses.create(admin.accountId(), request);
        return ResponseEntity.created(URI.create("/v1/admin/courses/" + course.id())).body(course);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List every Course",
            description = "In every state, unpaginated, in the order they were created, each as its own read shows it.")
    @ApiResponse(responseCode = "200", description = "Every Course",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminCourseList.class)))
    public AdminCourseList list() {
        return courses.list();
    }

    @GetMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Read a Course's document",
            description = "With what only reads show: `status`, and the `readiness` for each next state.")
    @ApiResponse(responseCode = "200", description = "The Course",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminCourse.class)))
    @ApiResponse(responseCode = "404", description = "`course-not-found`")
    public AdminCourse get(@PathVariable String courseId) {
        return courses.get(courseId);
    }

    @PutMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace a Course's document", description = """
            Takes the whole editable document, as the single read shows it: a field left out is cleared. What only \
            reads show (`id`, `status`, `readiness`) may stay in the body, and is ignored.""")
    @ApiResponse(responseCode = "200", description = "The Course as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminCourse.class)))
    @ApiResponse(responseCode = "400", description = """
            `invalid-request`, with a code for each field in `errors`; an unknown `area`, `icon` or `tone` is \
            `invalid-format`""")
    @ApiResponse(responseCode = "404", description = "`course-not-found`")
    @ApiResponse(responseCode = "409", description = """
            `slug-taken`; `slug-frozen` once the Course is no longer a Draft; `price-not-divisible-by-installments` \
            when both are set and `priceCents` leaves a remainder; `free-lesson-ineligible` when `freeLessonId` \
            names anything but a published Lesson of this Course; or `course-requirements-unmet` when the document \
            would leave a Course that is no longer a Draft without a field its state needs, all listed in `missing`: \
            an On sale Course can change its price and its Free lesson, but never clear them""")
    public AdminCourse update(@AuthenticationPrincipal AuthenticatedAccount admin, @PathVariable String courseId,
                              @Valid @RequestBody CourseDocument document) {
        return courses.update(admin.accountId(), courseId, document);
    }

    @PutMapping("/{courseId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Move a Course to another state", description = """
            A Course moves forward only: from Draft to Coming soon to On sale, or from Draft straight to On sale. \
            Going On sale needs a price, the most installments and a published Free lesson, besides the marketing \
            copy. Sending the state it is already in changes nothing, so a retry is harmless.""")
    @ApiResponse(responseCode = "200", description = "The Course as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminCourse.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "404", description = "`course-not-found`")
    @ApiResponse(responseCode = "409", description = """
            `course-cannot-move-back`, or `course-requirements-unmet` with every field the state needs and the Course \
            lacks listed in `missing`, as `readiness` shows them""")
    public AdminCourse changeStatus(@AuthenticationPrincipal AuthenticatedAccount admin, @PathVariable String courseId,
                                    @Valid @RequestBody CourseStatusChange change) {
        return courses.changeStatus(admin.accountId(), courseId, change);
    }

    @DeleteMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a Draft Course", description = "Only a Draft: a Course once announced stays.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "`course-not-found`")
    @ApiResponse(responseCode = "409", description = "`course-not-draft`")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedAccount admin,
                                       @PathVariable String courseId) {
        courses.delete(admin.accountId(), courseId);
        return ResponseEntity.noContent().build();
    }
}
