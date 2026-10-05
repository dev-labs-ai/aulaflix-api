package com.devlabs.aulaflix.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
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
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AdminLesson;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.VideoLinkRequest;
import com.devlabs.aulaflix.dto.VideoPlayback;
import com.devlabs.aulaflix.dto.VideoUpload;
import com.devlabs.aulaflix.service.VideoService;

/**
 * Every refusal is a ProblemDetail; an id of any shape answers like an unknown one. A signed URL is a bearer token
 * until it expires, so no answer that carries one may be stored along the way.
 */
@RestController
@RequestMapping("/v1/admin/lessons")
@SecurityRequirement(name = OpenApiConfiguration.BEARER)
@Tag(name = "Admin videos", description = "Uploading, linking and previewing a Lesson's video")
public class AdminVideoController {

    private final VideoService videos;

    public AdminVideoController(VideoService videos) {
        this.videos = videos;
    }

    @PostMapping("/{lessonId}/video-uploads")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Request an upload URL", description = """
            A presigned `PUT` for a new key under the Lesson's prefix, valid for an hour, for a Lesson in any state. \
            Upload a faststart H.264/AAC MP4 of at most 5 GiB straight to it, with the required headers: \
            `curl --upload-file aula.mp4 -H 'Content-Type: video/mp4' "$uploadUrl"`. Then link the `objectKey`. \
            Nothing is stored until then.""")
    @ApiResponse(responseCode = "200", description = "Where and how to upload",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = VideoUpload.class)))
    @ApiResponse(responseCode = "404", description = "`lesson-not-found`")
    public ResponseEntity<VideoUpload> requestUpload(@AuthenticationPrincipal AuthenticatedAccount admin,
                                                     @PathVariable String lessonId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(videos.requestUpload(admin.accountId(), lessonId));
    }

    @PutMapping("/{lessonId}/video")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Link an uploaded video", description = """
            Makes the uploaded object the Lesson's video, in place of any other, and reads its duration from the \
            file's header. Linking never publishes. Once linked, every other object under the Lesson's prefix is \
            deleted: the previous video and any abandoned upload.""")
    @ApiResponse(responseCode = "200", description = "The Lesson with its duration",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = AdminLesson.class)))
    @ApiResponse(responseCode = "400", description = "`invalid-request`, with a code for each field in `errors`")
    @ApiResponse(responseCode = "404", description = "`lesson-not-found`")
    @ApiResponse(responseCode = "409", description = """
            `video-not-found`: the key was not issued for this Lesson, or nothing was uploaded under it""")
    public AdminLesson link(@AuthenticationPrincipal AuthenticatedAccount admin, @PathVariable String lessonId,
                            @Valid @RequestBody VideoLinkRequest request) {
        return videos.link(admin.accountId(), lessonId, request);
    }

    @GetMapping("/{lessonId}/playback")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Preview a Lesson's video", description = """
            A presigned `GET` that plays the linked video, published or not, whatever the Course's state. A plain \
            `<video>` element plays it, seeking through range requests. Ask for a new one once it expires.""")
    @ApiResponse(responseCode = "200", description = "Where to play it from",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = VideoPlayback.class)))
    @ApiResponse(responseCode = "404", description = "`lesson-not-found`, or `video-not-linked`")
    public ResponseEntity<VideoPlayback> playback(@PathVariable String lessonId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(videos.adminPlayback(lessonId));
    }
}
