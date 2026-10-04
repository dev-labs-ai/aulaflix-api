package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.CourseStatus;

/** The state an Admin moves a Course to; sending the state it is already in changes nothing. */
public record CourseStatusChange(
        @NotNull(message = "required")
        @Schema(example = "COMING_SOON")
        CourseStatus status) {
}
