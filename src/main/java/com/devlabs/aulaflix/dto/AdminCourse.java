package com.devlabs.aulaflix.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.Area;
import com.devlabs.aulaflix.domain.CourseIcon;
import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.CourseTone;

/**
 * A Course as Admins see it, in any state: its editable document, plus what only reads show. A field that is not set,
 * or does not fit the state, is left out.
 */
public record AdminCourse(
        long id,
        String slug,
        String title,
        String summary,
        Area area,
        CourseIcon icon,
        CourseTone tone,
        List<String> about,
        List<String> learn,
        List<String> audience,
        List<String> plannedTopics,
        List<FaqEntry> faq,
        Integer priceCents,
        Integer pixDiscountPercent,
        Integer maxInstallments,
        Long freeLessonId,
        CourseStatus status,

        @Schema(description = "Left out once the Course is On sale, with no state left to move to")
        Readiness readiness,

        @Schema(description = "When the Course went Coming soon; left out if it never did")
        Instant comingSoonAt,

        @Schema(description = "When the Course went On sale")
        Instant onSaleAt) {
}
