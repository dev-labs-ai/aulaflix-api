package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A Lesson of the Syllabus. Until it is published it shows as "Em breve", without its slug, which can still change,
 * nor its duration, which changes with its video. It never carries a video URL.
 */
public record SyllabusLesson(
        long id,

        @Schema(description = "Its place in the Course, from 1, counted across the Modules, \"Em breve\" ones included",
                example = "7")
        int number,

        @Schema(example = "Rotas no Express")
        String title,

        boolean published,

        @Schema(description = "Only once published", example = "rotas-no-express")
        String slug,

        @Schema(description = "In whole seconds, only once published", example = "754")
        Integer durationSeconds) {
}
