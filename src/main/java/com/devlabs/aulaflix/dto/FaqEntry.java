package com.devlabs.aulaflix.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

public record FaqEntry(
        @NotBlank(message = "required")
        @Size(max = CourseDocument.ITEM_MAX_CHARACTERS, message = "too-long")
        @Schema(example = "Preciso saber JavaScript antes?")
        String question,

        @NotBlank(message = "required")
        @Size(max = CourseDocument.ITEM_MAX_CHARACTERS, message = "too-long")
        @Schema(example = "Sim, o básico: variáveis, funções, objetos e arrays.")
        String answer) {
}
