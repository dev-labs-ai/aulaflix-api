package com.devlabs.aulaflix.service;

import java.util.Optional;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.LessonEntity;
import com.devlabs.aulaflix.domain.entity.ModuleEntity;
import com.devlabs.aulaflix.exception.CourseNotFoundException;
import com.devlabs.aulaflix.exception.LessonNotFoundException;
import com.devlabs.aulaflix.exception.ModuleNotFoundException;
import com.devlabs.aulaflix.repository.CourseRepository;
import com.devlabs.aulaflix.repository.LessonRepository;
import com.devlabs.aulaflix.repository.ModuleRepository;

/**
 * Every change to a Course, to its outline, or to one of its Modules or Lessons, holds the Course's row lock until
 * its transaction ends, so that changes to one Course go one at a time. Each takes the id as the path carries it, so that an id of
 * any shape answers like an unknown one.
 */
@Component
class CatalogLocks {

    private final CourseRepository courses;
    private final ModuleRepository modules;
    private final LessonRepository lessons;

    CatalogLocks(CourseRepository courses, ModuleRepository modules, LessonRepository lessons) {
        this.courses = courses;
        this.modules = modules;
        this.lessons = lessons;
    }

    CourseEntity course(String courseId) {
        return PathIds.parse(courseId).flatMap(courses::findLockedById).orElseThrow(CourseNotFoundException::new);
    }

    ModuleEntity module(String moduleId) {
        return PathIds.parse(moduleId).flatMap(id -> underCourseLock(id, modules::findCourseIdById, modules::findById))
                .orElseThrow(ModuleNotFoundException::new);
    }

    LessonEntity lesson(String lessonId) {
        return PathIds.parse(lessonId).flatMap(this::findLesson).orElseThrow(LessonNotFoundException::new);
    }

    /** The Lesson under its Course's lock, or nothing once it is gone. */
    Optional<LessonEntity> findLesson(long lessonId) {
        return underCourseLock(lessonId, lessons::findCourseIdById, lessons::findById);
    }

    /**
     * Locks the Course of the Module or Lesson with the id, then reads it: read before the lock, it could be as it was
     * before the change that held the lock last.
     */
    private <T> Optional<T> underCourseLock(long id, Function<Long, Optional<Long>> courseIdOf,
                                            Function<Long, Optional<T>> find) {
        return courseIdOf.apply(id)
                .flatMap(courses::findLockedById)
                .flatMap(locked -> find.apply(id));
    }
}
