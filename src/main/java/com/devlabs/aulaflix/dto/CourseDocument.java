package com.devlabs.aulaflix.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.devlabs.aulaflix.domain.Area;
import com.devlabs.aulaflix.domain.CourseIcon;
import com.devlabs.aulaflix.domain.CourseTone;

/**
 * The editable document of a Course, which an Admin reads, edits and puts back whole: a field left out is cleared.
 * The texts are plain, with no Markdown, and each list is shown in the order given. The violation messages are the
 * API's field codes.
 */
public record CourseDocument(
        @NotBlank(message = "required")
        @Size(max = SLUG_MAX_CHARACTERS, message = "too-long")
        @Pattern(regexp = SLUG_PATTERN, message = "invalid-format")
        @Schema(example = "backend-com-node-js")
        String slug,

        @NotBlank(message = "required")
        @Size(max = TITLE_MAX_CHARACTERS, message = "too-long")
        @Schema(example = "Backend com Node.js")
        String title,

        @Size(max = SUMMARY_MAX_CHARACTERS, message = "too-long")
        @Schema(example = "Construa APIs REST com Node.js e TypeScript, até colocar o serviço no ar.")
        String summary,

        Area area,

        CourseIcon icon,

        CourseTone tone,

        @Size(max = LIST_MAX_ITEMS, message = "too-long")
        @Schema(description = "The paragraphs of \"Sobre o curso\"")
        List<@NotBlank(message = "required") @Size(max = ITEM_MAX_CHARACTERS, message = "too-long") String> about,

        @Size(max = LIST_MAX_ITEMS, message = "too-long")
        @Schema(description = "The items of \"O que você vai aprender\"")
        List<@NotBlank(message = "required") @Size(max = ITEM_MAX_CHARACTERS, message = "too-long") String> learn,

        @Size(max = LIST_MAX_ITEMS, message = "too-long")
        @Schema(description = "The items of \"Para quem é\"")
        List<@NotBlank(message = "required") @Size(max = ITEM_MAX_CHARACTERS, message = "too-long") String> audience,

        @Size(max = LIST_MAX_ITEMS, message = "too-long")
        @Schema(description = "The Planned topics (\"Conteúdo previsto\"), shown only while the Course is Coming soon")
        List<@NotBlank(message = "required") @Size(max = ITEM_MAX_CHARACTERS, message = "too-long") String>
                plannedTopics,

        @Size(max = LIST_MAX_ITEMS, message = "too-long")
        @Schema(description = "The questions about this Course only; those about every Course stay in the web")
        List<@NotNull(message = "required") @Valid FaqEntry> faq,

        @Min(value = 1, message = "out-of-range")
        @Schema(description = "The full price, in cents of BRL", example = "49700")
        Integer priceCents,

        @Min(value = 0, message = "out-of-range")
        @Max(value = 99, message = "out-of-range")
        @Schema(description = "The Pix discount, in whole percent", example = "10")
        Integer pixDiscountPercent,

        @Min(value = 1, message = "out-of-range")
        @Max(value = 12, message = "out-of-range")
        @Schema(description = "The most interest-free card installments; `priceCents` must divide by it exactly",
                example = "10")
        Integer maxInstallments,

        @Schema(description = """
                The one Lesson anyone may watch, Visitors included: a published Lesson of this Course, from any \
                Module. Once the Course is On sale it can move to another, but never be cleared""", example = "21")
        Long freeLessonId) {

    /** Lower-case words of letters and digits joined by single hyphens, as the web's URLs carry them. */
    public static final String SLUG_PATTERN = "[a-z0-9]+(-[a-z0-9]+)*";
    public static final int SLUG_MAX_CHARACTERS = 80;
    public static final int TITLE_MAX_CHARACTERS = 120;
    public static final int SUMMARY_MAX_CHARACTERS = 300;
    public static final int ITEM_MAX_CHARACTERS = 1000;
    public static final int LIST_MAX_ITEMS = 30;
}
