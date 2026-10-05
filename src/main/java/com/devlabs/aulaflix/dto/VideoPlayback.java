package com.devlabs.aulaflix.dto;

import java.net.URI;
import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** A URL that plays a Lesson's video straight from the storage, range requests included. */
public record VideoPlayback(
        @Schema(description = "A presigned GET, a bearer token until it expires",
                example = "https://media.aulaflix.com.br/videos/lessons/21/6f1c2c63-0f2e-4a8e-9a43-3a0d9c2e8b11.mp4"
                        + "?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=…")
        URI url,

        @Schema(description = "When the URL stops playing; ask for a new one then", example = "2026-10-04T23:00:00Z")
        Instant expiresAt) {
}
