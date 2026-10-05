package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.Area;
import com.devlabs.aulaflix.domain.CourseIcon;
import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.CourseTone;

/** The Course of an Enrollment, as "Meus cursos" shows it. */
public record EnrolledCourse(
        long id,
        String slug,
        String title,
        Area area,
        CourseIcon icon,
        CourseTone tone,

        @Schema(allowableValues = {"COMING_SOON", "ON_SALE"})
        CourseStatus status) {
}
