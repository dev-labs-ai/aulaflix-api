package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.LessonStatus;

/** Publishes a Lesson; sending it for a published Lesson changes nothing. */
public record LessonStatusChange(
        @NotNull(message = "required")
        @Schema(example = "PUBLISHED")
        LessonStatus status) {
}
