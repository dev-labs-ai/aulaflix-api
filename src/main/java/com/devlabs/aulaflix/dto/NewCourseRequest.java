package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** A Draft starts from a slug and a title; every other field comes with {@code PUT}. */
public record NewCourseRequest(
        @NotBlank(message = "required")
        @Size(max = CourseDocument.SLUG_MAX_CHARACTERS, message = "too-long")
        @Pattern(regexp = CourseDocument.SLUG_PATTERN, message = "invalid-format")
        @Schema(example = "backend-com-node-js")
        String slug,

        @NotBlank(message = "required")
        @Size(max = CourseDocument.TITLE_MAX_CHARACTERS, message = "too-long")
        @Schema(example = "Backend com Node.js")
        String title) {
}
