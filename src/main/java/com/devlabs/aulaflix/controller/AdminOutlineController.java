package com.devlabs.aulaflix.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import com.devlabs.aulaflix.dto.AdminLesson;
import com.devlabs.aulaflix.dto.AdminModule;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.LessonRequest;
import com.devlabs.aulaflix.dto.LessonStatusChange;
import com.devlabs.aulaflix.dto.ModuleRequest;
import com.devlabs.aulaflix.dto.OutlineModule;
import com.devlabs.aulaflix.service.OutlineService;

/** Every refusal is a ProblemDetail; an id of any shape answers like an unknown one. */
@RestController
@RequestMapping("/v1/admin")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@Tag(name = "Admin outline", description = "Shaping a Course's Modules and Lessons, and their order")
public class AdminOutlineController {

    private final OutlineService outlines;

    public AdminOutlineController(OutlineService outlines) {
        this.outlines = outlines;
    }

    @GetMapping("/courses/{courseId}/outline")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Read a Course's outline",
            description = "Its Modules in order, each with its Lessons in order.")
    @ApiResponse(responseCode = "200", description = "The outline",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = OutlineModule.class))))
    @ApiResponse(responseCode = "404", description = "`course-not-found`")
    public List<OutlineModule> outline(@PathVariable String courseId) {
        return outlines.outline(courseId);
    }

    @PutMapping("/courses/{courseId}/outline")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reorder a Course's outline", description = """
            Puts the Modules, and the Lessons within each, in the order given, moving Lessons between Modules, all in \
            one change. The body names exactly the Course's current Modules and Lessons, each once, as the read shows \
            them.""")
    @ApiResponse(responseCode = "200", description = "The outline as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    array = @ArraySchema(schema = @Schema(implementation = OutlineModule.class))))
    @ApiResponse(responseCode = "400", description = """
            `invalid-request`, with a code for each field in `errors`, named from the Module's index: \
            `[1].lessonIds[0]`""")
    @ApiResponse(responseCode = "404", description = "`course-not-found`")
    @ApiResponse(responseCode = "409", description = """
            `outline-mismatch`: a current Module or Lesson left out, one of another Course or none at all, or one \
            named twice""")
    public List<OutlineModule> reorder(@AuthenticationPrincipal AuthenticatedAccount admin,
                                       @PathVariable String courseId,
                                       @RequestBody List<@NotNull(message = "required") @Valid OutlineModule> outline) {
        return outlines.reorder(admin.accountId(), courseId, outline);
    }

    @PostMapping("/courses/{courseId}/modules")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a Module", description = "It goes at the end of the Course's outline, with no Lessons.")
    @ApiResponse(responseCode = "201", description = "Added; `Location` is the new Module's address",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminModule.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "404", description = "`course-not-found`")
    public ResponseEntity<AdminModule> addModule(@AuthenticationPrincipal AuthenticatedAccount admin,
                                                 @PathVariable String courseId,
                                                 @Valid @RequestBody ModuleRequest request) {
        AdminModule module = outlines.addModule(admin.accountId(), courseId, request);
        return ResponseEntity.created(URI.create("/v1/admin/modules/" + module.id())).body(module);
    }

    @PutMapping("/modules/{moduleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rename a Module", description = "Its place in the outline and its Lessons stay as they are.")
    @ApiResponse(responseCode = "200", description = "The Module as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminModule.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "404", description = "`module-not-found`")
    public AdminModule renameModule(@AuthenticationPrincipal AuthenticatedAccount admin, @PathVariable String moduleId,
                                    @Valid @RequestBody ModuleRequest request) {
        return outlines.renameModule(admin.accountId(), moduleId, request);
    }

    @DeleteMapping("/modules/{moduleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete an empty Module",
            description = "Only a Module without Lessons: move its Lessons elsewhere, or delete them, first.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "`module-not-found`")
    @ApiResponse(responseCode = "409", description = "`module-not-empty`")
    public ResponseEntity<Void> deleteModule(@AuthenticationPrincipal AuthenticatedAccount admin,
                                             @PathVariable String moduleId) {
        outlines.deleteModule(admin.accountId(), moduleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/modules/{moduleId}/lessons")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a Lesson", description = "It goes at the end of the Module, unpublished.")
    @ApiResponse(responseCode = "201", description = "Added; `Location` is the new Lesson's address",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminLesson.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "404", description = "`module-not-found`")
    @ApiResponse(responseCode = "409", description = "`lesson-slug-taken`")
    public ResponseEntity<AdminLesson> addLesson(@AuthenticationPrincipal AuthenticatedAccount admin,
                                                 @PathVariable String moduleId,
                                                 @Valid @RequestBody LessonRequest request) {
        AdminLesson lesson = outlines.addLesson(admin.accountId(), moduleId, request);
        return ResponseEntity.created(URI.create("/v1/admin/lessons/" + lesson.id())).body(lesson);
    }

    @PutMapping("/lessons/{lessonId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Edit a Lesson", description = """
            Its title, and its slug until it is published. Its place in the outline stays as it is.""")
    @ApiResponse(responseCode = "200", description = "The Lesson as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminLesson.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "404", description = "`lesson-not-found`")
    @ApiResponse(responseCode = "409", description = """
            `lesson-slug-taken`, or `lesson-slug-frozen` once the Lesson is published: its title may still change""")
    public AdminLesson editLesson(@AuthenticationPrincipal AuthenticatedAccount admin, @PathVariable String lessonId,
                                  @Valid @RequestBody LessonRequest request) {
        return outlines.editLesson(admin.accountId(), lessonId, request);
    }

    @DeleteMapping("/lessons/{lessonId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete an unpublished Lesson",
            description = "Only a Lesson never published, with its videos: a published Lesson stays.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "`lesson-not-found`")
    @ApiResponse(responseCode = "409", description = "`lesson-published`")
    public ResponseEntity<Void> deleteLesson(@AuthenticationPrincipal AuthenticatedAccount admin,
                                             @PathVariable String lessonId) {
        outlines.deleteLesson(admin.accountId(), lessonId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/lessons/{lessonId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Publish a Lesson", description = """
            Once published, a Lesson shows in its Course's Syllabus with its slug and duration, and is never \
            unpublished nor deleted; its slug is frozen, but its video can still be replaced. It needs a linked video. \
            Publishing a published Lesson changes nothing, so a retry is harmless.""")
    @ApiResponse(responseCode = "200", description = "The Lesson as it now is",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminLesson.class)))
    @ApiResponse(responseCode = "400", description = """
            `invalid-request`, with a code for each field in `errors`: `PUBLISHED` is the only status""")
    @ApiResponse(responseCode = "404", description = "`lesson-not-found`")
    @ApiResponse(responseCode = "409", description = "`video-required`: the Lesson has no video yet")
    public AdminLesson publishLesson(@AuthenticationPrincipal AuthenticatedAccount admin,
                                     @PathVariable String lessonId,
                                     @Valid @RequestBody LessonStatusChange change) {
        return outlines.publishLesson(admin.accountId(), lessonId);
    }
}
