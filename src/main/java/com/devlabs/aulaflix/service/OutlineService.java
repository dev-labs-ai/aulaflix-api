package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.LessonEntity;
import com.devlabs.aulaflix.domain.entity.ModuleEntity;
import com.devlabs.aulaflix.dto.AdminLesson;
import com.devlabs.aulaflix.dto.AdminModule;
import com.devlabs.aulaflix.dto.LessonRequest;
import com.devlabs.aulaflix.dto.ModuleRequest;
import com.devlabs.aulaflix.dto.OutlineModule;
import com.devlabs.aulaflix.exception.CourseNotFoundException;
import com.devlabs.aulaflix.exception.LessonPublishedException;
import com.devlabs.aulaflix.exception.LessonSlugFrozenException;
import com.devlabs.aulaflix.exception.LessonSlugTakenException;
import com.devlabs.aulaflix.exception.ModuleNotEmptyException;
import com.devlabs.aulaflix.exception.OutlineMismatchException;
import com.devlabs.aulaflix.exception.VideoRequiredException;
import com.devlabs.aulaflix.repository.CourseRepository;
import com.devlabs.aulaflix.repository.LessonRepository;
import com.devlabs.aulaflix.repository.ModuleRepository;

/**
 * The outline of each Course, as Admins shape it: its Modules and Lessons, and their order. Every change holds the
 * Course's lock, so that changes to one outline go one at a time.
 */
@Service
public class OutlineService {

    private static final Logger log = LoggerFactory.getLogger(OutlineService.class);

    private final CourseRepository courses;
    private final ModuleRepository modules;
    private final LessonRepository lessons;
    private final CatalogLocks locks;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public OutlineService(CourseRepository courses, ModuleRepository modules, LessonRepository lessons,
                          CatalogLocks locks, ApplicationEventPublisher events, Clock clock) {
        this.courses = courses;
        this.modules = modules;
        this.lessons = lessons;
        this.locks = locks;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<OutlineModule> outline(String courseId) {
        long id = PathIds.parse(courseId).filter(courses::existsById).orElseThrow(CourseNotFoundException::new);
        return outlineOf(modules.findByCourseIdOrderByPosition(id), lessons.findByCourseIdOrderByPosition(id));
    }

    /** Puts the Modules, and the Lessons within each, in the order given, moving Lessons between Modules. */
    @Transactional
    public List<OutlineModule> reorder(long adminId, String courseId, List<OutlineModule> outline) {
        CourseEntity course = locks.course(courseId);
        List<ModuleEntity> courseModules = modules.findByCourseIdOrderByPosition(course.getId());
        List<LessonEntity> courseLessons = lessons.findByCourseIdOrderByPosition(course.getId());
        Map<Long, ModuleEntity> modulesById = byId(courseModules, ModuleEntity::getId);
        Map<Long, LessonEntity> lessonsById = byId(courseLessons, LessonEntity::getId);
        requireExactly(outline, modulesById.keySet(), lessonsById.keySet());
        for (int index = 0; index < outline.size(); index++) {
            ModuleEntity module = modulesById.get(outline.get(index).moduleId());
            module.setPosition(index);
            placeLessons(module, outline.get(index).lessonIds(), lessonsById);
        }
        log.info("Admin {} reordered the outline of Course {}", adminId, course.getId());
        return outlineOf(courseModules.stream().sorted(Comparator.comparingInt(ModuleEntity::getPosition)).toList(),
                courseLessons.stream().sorted(Comparator.comparingInt(LessonEntity::getPosition)).toList());
    }

    /** Appends the Module to the end of its Course's outline. */
    @Transactional
    public AdminModule addModule(long adminId, String courseId, ModuleRequest request) {
        CourseEntity course = locks.course(courseId);
        int position = modules.findNextPositionByCourseId(course.getId());
        ModuleEntity module = modules.save(new ModuleEntity(course, position, request.title()));
        log.info("Admin {} added Module {} to Course {}", adminId, module.getId(), course.getId());
        return moduleView(module);
    }

    @Transactional
    public AdminModule renameModule(long adminId, String moduleId, ModuleRequest request) {
        ModuleEntity module = locks.module(moduleId);
        module.setTitle(request.title());
        log.info("Admin {} renamed Module {}", adminId, module.getId());
        return moduleView(module);
    }

    /** Only an empty Module: its Lessons are moved or deleted first, never along with it. */
    @Transactional
    public void deleteModule(long adminId, String moduleId) {
        ModuleEntity module = locks.module(moduleId);
        if (lessons.existsByModuleId(module.getId())) {
            throw new ModuleNotEmptyException();
        }
        modules.delete(module);
        log.info("Admin {} deleted Module {}", adminId, module.getId());
    }

    /** Appends the Lesson to the end of its Module. */
    @Transactional
    public AdminLesson addLesson(long adminId, String moduleId, LessonRequest request) {
        ModuleEntity module = locks.module(moduleId);
        requireFreeSlug(module.getCourse().getId(), request.slug());
        int position = lessons.findNextPositionByModuleId(module.getId());
        LessonEntity lesson = lessons.save(new LessonEntity(module, position, request.title(), request.slug()));
        log.info("Admin {} added Lesson {} to Module {}", adminId, lesson.getId(), module.getId());
        return lessonView(lesson);
    }

    @Transactional
    public AdminLesson editLesson(long adminId, String lessonId, LessonRequest request) {
        LessonEntity lesson = locks.lesson(lessonId);
        requireSlugChangeAllowed(lesson, request.slug());
        lesson.setTitle(request.title());
        lesson.setSlug(request.slug());
        log.info("Admin {} edited Lesson {}", adminId, lesson.getId());
        return lessonView(lesson);
    }

    /** Only a Lesson never published, which no Student has seen; its videos go once the deletion commits. */
    @Transactional
    public void deleteLesson(long adminId, String lessonId) {
        LessonEntity lesson = locks.lesson(lessonId);
        if (lesson.isPublished()) {
            throw new LessonPublishedException();
        }
        lessons.delete(lesson);
        events.publishEvent(new LessonsDeleted(List.of(lesson.getId())));
        log.info("Admin {} deleted Lesson {}", adminId, lesson.getId());
    }

    /**
     * Publishing a published Lesson changes nothing, so a retry is harmless. The instant is cut to the microseconds
     * PostgreSQL keeps, so that the answer shows what every later read will.
     */
    @Transactional
    public AdminLesson publishLesson(long adminId, String lessonId) {
        LessonEntity lesson = locks.lesson(lessonId);
        if (lesson.isPublished()) {
            return lessonView(lesson);
        }
        if (lesson.getVideoObjectKey() == null) {
            throw new VideoRequiredException();
        }
        lesson.publish(clock.instant().truncatedTo(ChronoUnit.MICROS));
        log.info("Admin {} published Lesson {}", adminId, lesson.getId());
        return lessonView(lesson);
    }

    /** Every current Module and Lesson, each named once: none left out, none added, none twice. */
    private static void requireExactly(List<OutlineModule> outline, Set<Long> moduleIds, Set<Long> lessonIds) {
        List<Long> namedModuleIds = outline.stream().map(OutlineModule::moduleId).toList();
        List<Long> namedLessonIds = outline.stream().flatMap(module -> module.lessonIds().stream()).toList();
        if (!namesEachOnce(namedModuleIds, moduleIds) || !namesEachOnce(namedLessonIds, lessonIds)) {
            throw new OutlineMismatchException();
        }
    }

    /** As many names as ids, and every id among them, leaves no room for a name twice. */
    private static boolean namesEachOnce(List<Long> named, Set<Long> ids) {
        return named.size() == ids.size() && new HashSet<>(named).equals(ids);
    }

    private static void placeLessons(ModuleEntity module, List<Long> lessonIds, Map<Long, LessonEntity> lessonsById) {
        for (int index = 0; index < lessonIds.size(); index++) {
            LessonEntity lesson = lessonsById.get(lessonIds.get(index));
            lesson.setModule(module);
            lesson.setPosition(index);
        }
    }

    /** The slug is the web's address for a published Lesson, so it stays put once the Lesson is published. */
    private void requireSlugChangeAllowed(LessonEntity lesson, String slug) {
        if (lesson.getSlug().equals(slug)) {
            return;
        }
        if (lesson.isPublished()) {
            throw new LessonSlugFrozenException();
        }
        requireFreeSlug(lesson.getCourse().getId(), slug);
    }

    private void requireFreeSlug(long courseId, String slug) {
        if (lessons.existsByCourseIdAndSlug(courseId, slug)) {
            throw new LessonSlugTakenException();
        }
    }

    /** The Modules and Lessons each in their order: the Lessons' positions order them within their Module. */
    private static List<OutlineModule> outlineOf(List<ModuleEntity> orderedModules, List<LessonEntity> orderedLessons) {
        Map<Long, List<Long>> lessonIdsByModuleId = orderedLessons.stream().collect(Collectors.groupingBy(
                lesson -> lesson.getModule().getId(),
                Collectors.mapping(LessonEntity::getId, Collectors.toList())));
        return orderedModules.stream()
                .map(module -> new OutlineModule(module.getId(),
                        lessonIdsByModuleId.getOrDefault(module.getId(), List.of())))
                .toList();
    }

    private static <T> Map<Long, T> byId(List<T> entities, Function<T, Long> id) {
        return entities.stream().collect(Collectors.toMap(id, Function.identity()));
    }

    private static AdminModule moduleView(ModuleEntity module) {
        return new AdminModule(module.getId(), module.getCourse().getId(), module.getTitle());
    }

    static AdminLesson lessonView(LessonEntity lesson) {
        return new AdminLesson(lesson.getId(), lesson.getModule().getId(), lesson.getTitle(), lesson.getSlug(),
                lesson.getDurationSeconds(), lesson.isPublished(), lesson.getPublishedAt());
    }
}
