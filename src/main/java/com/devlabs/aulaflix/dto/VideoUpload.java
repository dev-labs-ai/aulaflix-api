package com.devlabs.aulaflix.dto;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/** Where and how to upload a Lesson's video, straight to the storage, and the key to link once it is there. */
public record VideoUpload(
        @Schema(description = "A presigned PUT, a bearer token until it expires",
                example = "https://media.aulaflix.com.br/videos/lessons/21/6f1c2c63-0f2e-4a8e-9a43-3a0d9c2e8b11.mp4"
                        + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=…")
        URI uploadUrl,

        @Schema(description = "What to send to the link once the upload completes",
                example = "lessons/21/6f1c2c63-0f2e-4a8e-9a43-3a0d9c2e8b11.mp4")
        String objectKey,

        @Schema(description = "When the URL stops accepting an upload", example = "2026-10-04T20:00:00Z")
        Instant expiresAt,

        @Schema(description = "The headers the PUT must carry, which the signature covers",
                example = "{\"Content-Type\": \"video/mp4\"}")
        Map<String, String> requiredHeaders) {
}
