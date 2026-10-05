package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One of the Student's active Enrollments, as "Meus cursos" lists it. */
public record StudentEnrollment(
        EnrolledCourse course,

        @Schema(description = "Only while the Course is On sale: a Coming soon Course has no Progress until its launch")
        Progress progress) {
}
