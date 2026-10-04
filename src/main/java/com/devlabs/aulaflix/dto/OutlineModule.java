package com.devlabs.aulaflix.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

/** One Module of a Course's outline, in order, with its Lessons in order. The violation messages are field codes. */
public record OutlineModule(
        @NotNull(message = "required")
        @Schema(example = "11")
        Long moduleId,

        @NotNull(message = "required")
        @Schema(example = "[21, 22, 23]")
        List<@NotNull(message = "required") Long> lessonIds) {
}
