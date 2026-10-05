package com.devlabs.aulaflix.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.devlabs.aulaflix.domain.entity.LessonEntity;
import com.devlabs.aulaflix.domain.entity.ModuleEntity;
import com.devlabs.aulaflix.dto.SyllabusLesson;
import com.devlabs.aulaflix.dto.SyllabusModule;

/**
 * The Syllabus, numbered as everyone sees it. Nothing stores a number: each follows from the outline, so reordering
 * it renumbers the Syllabus.
 */
final class Syllabus {

    private Syllabus() {
    }

    /**
     * The Modules that have Lessons, numbered among themselves, with their Lessons numbered across them; the Lessons'
     * positions order them within their Module.
     */
    static List<SyllabusModule> of(List<ModuleEntity> orderedModules, List<LessonEntity> orderedLessons) {
        Map<Long, List<LessonEntity>> lessonsByModuleId = orderedLessons.stream()
                .collect(Collectors.groupingBy(lesson -> lesson.getModule().getId()));
        List<SyllabusModule> syllabus = new ArrayList<>();
        int lessonNumber = 0;
        for (ModuleEntity module : orderedModules) {
            List<LessonEntity> lessons = lessonsByModuleId.getOrDefault(module.getId(), List.of());
            if (lessons.isEmpty()) {
                continue;
            }
            List<SyllabusLesson> numbered = new ArrayList<>();
            for (LessonEntity lesson : lessons) {
                numbered.add(lessonOf(lesson, ++lessonNumber));
            }
            syllabus.add(new SyllabusModule(syllabus.size() + 1, module.getTitle(), numbered));
        }
        return syllabus;
    }

    /** An "Em breve" Lesson's slug can still change, and its duration changes with its video. */
    private static SyllabusLesson lessonOf(LessonEntity lesson, int number) {
        boolean published = lesson.isPublished();
        return new SyllabusLesson(lesson.getId(), number, lesson.getTitle(), published,
                published ? lesson.getSlug() : null, published ? lesson.getDurationSeconds() : null);
    }
}
