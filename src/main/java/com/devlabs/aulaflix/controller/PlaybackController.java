package com.devlabs.aulaflix.controller;

import java.util.Optional;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devlabs.aulaflix.config.OpenApiConfiguration;
import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.dto.VideoPlayback;
import com.devlabs.aulaflix.service.VideoService;

/**
 * Playback for everyone but the Admin, who previews videos through their own endpoint; an id of any shape answers like
 * an unknown one. A signed URL is a bearer token until it expires, so no answer that carries one may be stored along
 * the way.
 */
@RestController
@RequestMapping("/v1/lessons")
@Tag(name = "Playback", description = "Playing a Lesson's video: the Free lesson for anyone, any other with a session")
public class PlaybackController {

    private final VideoService videos;

    public PlaybackController(VideoService videos) {
        this.videos = videos;
    }

    /** The session is optional: the BFF's key alone, or with a session's token. */
    @GetMapping("/{lessonId}/playback")
    @PreAuthorize("!hasRole('ADMIN')")
    @SecurityRequirement(name = OpenApiConfiguration.BFF_KEY)
    @SecurityRequirement(name = OpenApiConfiguration.BEARER)
    @Operation(summary = "Play a Lesson's video", description = """
            A presigned `GET` of the video of a published Lesson of an On sale Course, signed on every call with a \
            key that only reads. A plain `<video>` element plays it, seeking through range requests; when it fails \
            once expired, ask for a new one and resume at `currentTime`. The Free lesson plays for anyone, without a \
            session; any other Lesson needs one. A token that is sent must be valid, even for the Free lesson: on \
            its 401, clear the cookie and ask again without it. Without a session, each IP gets a limited number of \
            plays an hour, whatever they answer.""")
    @ApiResponse(responseCode = "200", description = "Where to play it from",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = VideoPlayback.class)))
    @ApiResponse(responseCode = "403", description = "`forbidden`: an Admin's session, even for the Free lesson")
    @ApiResponse(responseCode = "404", description = """
            `lesson-not-found`: no Lesson has the id, or it is "Em breve", or its Course is not On sale""")
    public ResponseEntity<VideoPlayback> playback(@PathVariable String lessonId,
                                                  @AuthenticationPrincipal AuthenticatedAccount viewer) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(videos.playback(lessonId, Optional.ofNullable(viewer)));
    }
}
