package com.devlabs.aulaflix.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.Area;
import com.devlabs.aulaflix.domain.CourseIcon;
import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.CourseTone;

/** A Course as the catalog lists it, the same for everyone. A field that does not fit its state is left out. */
public record CourseListItem(
        long id,
        String slug,
        String title,
        String summary,
        Area area,
        CourseIcon icon,
        CourseTone tone,

        @Schema(allowableValues = {"COMING_SOON", "ON_SALE"})
        CourseStatus status,

        @Schema(description = "Only while the Course is On sale")
        CoursePricing pricing,

        @Schema(description = "How many Lessons the Course has, \"Em breve\" ones included; only while it is On sale",
                example = "12")
        Integer lessonCount,

        @Schema(description = "How many Planned topics the Course announces; only while it is Coming soon")
        Integer plannedTopicCount) {
}
