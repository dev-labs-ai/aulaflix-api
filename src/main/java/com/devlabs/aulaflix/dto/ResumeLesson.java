package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The Lesson "Continuar" opens: the one the Student left unfinished, or else the next one. Always a published one. */
public record ResumeLesson(
        @Schema(example = "41")
        long id,

        @Schema(example = "estado-com-hooks")
        String slug,

        @Schema(description = "The Lesson's number in the Syllabus, \"Em breve\" Lessons counted", example = "5")
        int number,

        @Schema(example = "Estado com hooks")
        String title) {
}
