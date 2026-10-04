package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an Admin sets on a Lesson, the same to add one as to edit it. The slug follows the Course's rules, since both
 * travel in the web's URLs.
 */
public record LessonRequest(
        @NotBlank(message = "required")
        @Size(max = TITLE_MAX_CHARACTERS, message = "too-long")
        @Schema(example = "Estado com hooks")
        String title,

        @NotBlank(message = "required")
        @Size(max = CourseDocument.SLUG_MAX_CHARACTERS, message = "too-long")
        @Pattern(regexp = CourseDocument.SLUG_PATTERN, message = "invalid-format")
        @Schema(description = "Unique within the Lesson's Course", example = "estado-com-hooks")
        String slug) {

    static final int TITLE_MAX_CHARACTERS = 120;
}
