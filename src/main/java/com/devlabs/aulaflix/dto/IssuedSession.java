package com.devlabs.aulaflix.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** A session just opened, the only time its token is ever shown. */
public record IssuedSession(
        @Schema(description = "Opaque; send it as `Authorization: Bearer <token>`.",
                example = "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s")
        String token,

        @Schema(description = "The absolute end of the session. It ends earlier after a stretch without use.",
                example = "2026-10-04T20:00:00Z")
        Instant expiresAt) {
}
