package com.devlabs.aulaflix.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * "Meus cursos": the Student's active Enrollments, unpaginated, the most recently visited Course first; the Courses not
 * visited yet come after, oldest Enrollment first.
 */
public record StudentEnrollmentList(
        List<StudentEnrollment> items,

        @Schema(description = """
                The Course to show with "Continuar de onde parou": the most recently visited one with a published \
                Lesson left to complete. Omitted when no Course has one.""", example = "3")
        Long highlightedCourseId) {
}
