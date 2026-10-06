package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The Lesson whose page just mounted. The violation messages are the API's field codes. The id is read as text, so
 * that an id of any shape answers like an unknown Lesson.
 */
public record LessonVisitRequest(
        @NotNull(message = "required")
        @Schema(types = "integer", format = "int64", example = "41")
        String lessonId) {
}
