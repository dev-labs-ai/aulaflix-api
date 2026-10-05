package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** A Lesson as Admins see it. Its place in the Course, and so its number, comes with the outline. */
public record AdminLesson(
        long id,
        long moduleId,
        String title,
        String slug,

        @Schema(description = "Read from the linked video's file; omitted while the Lesson has no video.",
                example = "754")
        Integer durationSeconds) {
}
