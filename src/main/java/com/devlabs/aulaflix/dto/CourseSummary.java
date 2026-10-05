package com.devlabs.aulaflix.dto;

import com.devlabs.aulaflix.domain.CourseStatus;

/** Enough of a Course to name it beside something of its own. */
public record CourseSummary(long id, String slug, String title, CourseStatus status) {
}
