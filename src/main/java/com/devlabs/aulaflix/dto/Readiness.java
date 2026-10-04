package com.devlabs.aulaflix.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** For each state a Course can move to next, the fields still missing; an empty list means it is ready. */
public record Readiness(
        @Schema(description = "Missing to go Coming soon; only while the Course is a Draft",
                example = "[\"summary\", \"plannedTopics\"]")
        List<String> comingSoon,

        @Schema(description = "Missing to go On sale; only until the Course is On sale",
                example = "[\"priceCents\", \"maxInstallments\", \"freeLessonId\"]")
        List<String> onSale) {
}
