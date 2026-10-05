package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** The Lesson whose page just mounted. The violation messages are the API's field codes. */
public record LessonVisitRequest(
        @NotNull(message = "required")
        @Schema(example = "41")
        Long lessonId) {
}
