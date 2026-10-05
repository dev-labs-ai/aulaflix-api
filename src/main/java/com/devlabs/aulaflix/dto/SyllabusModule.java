package com.devlabs.aulaflix.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** A Module of the Syllabus, which leaves out the Modules that have no Lessons. */
public record SyllabusModule(
        @Schema(description = "Its place among the Modules shown, from 1", example = "2")
        int number,

        @Schema(example = "Rotas e respostas")
        String title,

        @Schema(description = "Its Lessons in order, \"Em breve\" ones included")
        List<SyllabusLesson> lessons) {
}
