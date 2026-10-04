package com.devlabs.aulaflix.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.Area;
import com.devlabs.aulaflix.domain.CourseIcon;
import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.CourseTone;

/**
 * A Course as its page shows it, the same for everyone: what the list shows, plus the marketing copy. A field that
 * does not fit its state is left out.
 */
public record CourseDetail(
        long id,
        String slug,
        String title,
        String summary,
        Area area,
        CourseIcon icon,
        CourseTone tone,

        @Schema(allowableValues = {"COMING_SOON", "ON_SALE"})
        CourseStatus status,

        @Schema(description = "How many Planned topics the Course announces; only while it is Coming soon")
        Integer plannedTopicCount,

        @Schema(description = "The paragraphs of \"Sobre o curso\"")
        List<String> about,

        @Schema(description = "The items of \"O que você vai aprender\"")
        List<String> learn,

        @Schema(description = "The items of \"Para quem é\"")
        List<String> audience,

        @Schema(description = "The questions about this Course only")
        List<FaqEntry> faq,

        @Schema(description = "The Planned topics (\"Conteúdo previsto\"); only while the Course is Coming soon")
        List<String> plannedTopics) {
}
