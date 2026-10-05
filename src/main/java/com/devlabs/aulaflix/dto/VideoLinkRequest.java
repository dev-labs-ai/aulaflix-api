package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;

import io.swagger.v3.oas.annotations.media.Schema;

/** Which uploaded object becomes the Lesson's video. */
public record VideoLinkRequest(
        @NotBlank(message = "required")
        @Schema(description = "The `objectKey` of an upload URL issued for this Lesson",
                example = "lessons/21/6f1c2c63-0f2e-4a8e-9a43-3a0d9c2e8b11.mp4")
        String objectKey) {
}
