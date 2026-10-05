package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.Standing;

/** A Student's Progress in a Course, counted for the progress bar. */
public record Progress(
        @Schema(description = "Lessons the Student completed", example = "4")
        int completed,

        @Schema(description = "Published Lessons", example = "10")
        int published,

        @Schema(description = "Every Lesson, \"Em breve\" ones included", example = "12")
        int total,

        @Schema(description = "`completed` ÷ `total`, rounded down: 100 only once Finished", example = "33")
        int percent,

        Standing standing) {
}
