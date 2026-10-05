package com.devlabs.aulaflix.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** One of the Student's active Enrollments, with the Lessons they completed, for the Course's page. */
public record StudentEnrollmentDetail(
        EnrolledCourse course,

        @Schema(description = "Only while the Course is On sale: a Coming soon Course has no Progress until its launch")
        Progress progress,

        @Schema(description = "Where \"Continuar\" goes; only while the Course is On sale")
        ResumeLesson resumeLesson,

        @Schema(description = "The ids of the Lessons the Student completed, ascending; only while the Course is On sale")
        List<Long> completedLessonIds) {
}
