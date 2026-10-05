package com.devlabs.aulaflix.controller;

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
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.LessonVisitRequest;
import com.devlabs.aulaflix.dto.StudentEnrollmentDetail;
import com.devlabs.aulaflix.dto.StudentEnrollmentList;
import com.devlabs.aulaflix.service.LearningService;

/**
 * The Student's own Enrollments and Progress, under the singleton {@code /v1/account}, so there is no id to authorize:
 * every answer is the calling Student's. Course and Lesson ids of any shape answer like unknown ones.
 */
@RestController
@RequestMapping("/v1/account")
@Tag(name = "Learning", description = "\"Meus cursos\" and the Lessons a Student marks as completed")
public class LearningController {

    private static final String LESSON_NOT_FOUND = """
            `lesson-not-found`: no Lesson has the id, or it is "Em breve", or its Course is not On sale""";

    private static final String ENROLLMENT_REQUIRED = """
            `enrollment-required`: the Student has no active Enrollment in the Lesson's Course""";

    private final LearningService learning;

    public LearningController(LearningService learning) {
        this.learning = learning;
    }

    @GetMapping("/enrollments")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "List the Student's active Enrollments", description = """
            "Meus cursos": every active Enrollment, with its Course, the Student's Progress in it and its \
            `resumeLesson`. The most recently visited Course comes first, and the ones never visited follow, oldest \
            Enrollment first. `highlightedCourseId` is the most recently visited Course with a published Lesson left \
            to complete. An Enrollment in a Coming soon Course comes without `progress` and `resumeLesson` until the \
            launch, and is never highlighted. An ended Enrollment is not listed.""")
    @ApiResponse(responseCode = "200", description = "The active Enrollments, unpaginated",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentEnrollmentList.class)))
    public StudentEnrollmentList enrollments(@AuthenticationPrincipal AuthenticatedAccount student) {
        return learning.enrollments(student.accountId());
    }

    @GetMapping("/enrollments/{courseId}")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Read the Student's active Enrollment in a Course", description = """
            The Enrollment as the list shows it, with the ids of the Lessons the Student completed, for the Course's \
            page. An Enrollment in a Coming soon Course comes without `progress`, `resumeLesson` and \
            `completedLessonIds` until the launch.""")
    @ApiResponse(responseCode = "200", description = "The active Enrollment in the Course",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = StudentEnrollmentDetail.class)))
    @ApiResponse(responseCode = "404", description = """
            `enrollment-not-found`: the Student has no active Enrollment in a Course with the id, or it ended""")
    public StudentEnrollmentDetail enrollment(@AuthenticationPrincipal AuthenticatedAccount student,
                                              @PathVariable String courseId) {
        return learning.enrollment(student.accountId(), courseId);
    }

    @PutMapping("/completed-lessons/{lessonId}")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Mark a Lesson as completed", description = """
            Idempotent: marking a completed Lesson again changes nothing. The mark belongs to the Student, not to the \
            Enrollment, so it outlives an Enrollment that ends and shows again with a new one.""")
    @ApiResponse(responseCode = "204", description = "The Lesson is completed")
    @ApiResponse(responseCode = "404", description = LESSON_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = ENROLLMENT_REQUIRED)
    public ResponseEntity<Void> complete(@AuthenticationPrincipal AuthenticatedAccount student,
                                         @PathVariable String lessonId) {
        learning.complete(student.accountId(), lessonId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/completed-lessons/{lessonId}")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Take a Lesson's completed mark back", description = """
            Idempotent: taking back a mark the Lesson doesn't have changes nothing.""")
    @ApiResponse(responseCode = "204", description = "The Lesson is not completed")
    @ApiResponse(responseCode = "404", description = LESSON_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = ENROLLMENT_REQUIRED)
    public ResponseEntity<Void> uncomplete(@AuthenticationPrincipal AuthenticatedAccount student,
                                           @PathVariable String lessonId) {
        learning.uncomplete(student.accountId(), lessonId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/lesson-visits")
    @PreAuthorize("hasRole('STUDENT')")
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Record a visit to a Lesson", description = """
            The Lesson's page calls this when it mounts: no read records a visit. Only the last visit per Course is \
            kept, and it moves the Course's `resumeLesson` and its place in "Meus cursos".""")
    @ApiResponse(responseCode = "204", description = "The Lesson is the last one the Student opened in its Course")
    @ApiResponse(responseCode = "404", description = LESSON_NOT_FOUND)
    @ApiResponse(responseCode = "409", description = ENROLLMENT_REQUIRED)
    public ResponseEntity<Void> visit(@AuthenticationPrincipal AuthenticatedAccount student,
                                      @Valid @RequestBody LessonVisitRequest request) {
        learning.visit(student.accountId(), request.lessonId());
        return ResponseEntity.noContent().build();
    }
}
