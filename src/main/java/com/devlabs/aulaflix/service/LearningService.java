package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.EnrollmentEntity;
import com.devlabs.aulaflix.domain.entity.LessonEntity;
import com.devlabs.aulaflix.dto.EnrolledCourse;
import com.devlabs.aulaflix.dto.Progress;
import com.devlabs.aulaflix.dto.ResumeLesson;
import com.devlabs.aulaflix.dto.StudentEnrollment;
import com.devlabs.aulaflix.dto.StudentEnrollmentDetail;
import com.devlabs.aulaflix.dto.StudentEnrollmentList;
import com.devlabs.aulaflix.exception.EnrollmentNotFoundException;
import com.devlabs.aulaflix.exception.EnrollmentRequiredException;
import com.devlabs.aulaflix.exception.LessonNotFoundException;
import com.devlabs.aulaflix.repository.CompletedLessonRepository;
import com.devlabs.aulaflix.repository.LessonRepository;
import com.devlabs.aulaflix.repository.LessonVisitRepository;
import com.devlabs.aulaflix.repository.LessonVisitRepository.LastVisit;
import com.devlabs.aulaflix.service.ResumeLessons.OutlineLesson;

/**
 * The Learning module: the Lessons a Student marks as completed and the last one they opened in each Course, and what
 * follows from them: the Progress, the Resume lesson and the highlighted Course. Both belong to the Student, not to an
 * Enrollment, but are reached only through an active one, so they come back intact with a new Enrollment. A Coming
 * soon Course has no Progress and no Resume lesson until its launch.
 */
@Service
public class LearningService {

    /** The most recently visited first, then the Courses never visited, in the order they came. */
    private static final Comparator<Learning> BY_LAST_VISIT = Comparator.comparing(Learning::lastVisitedAt,
            Comparator.nullsLast(Comparator.reverseOrder()));

    private final EnrollmentService enrollments;
    private final LessonRepository lessons;
    private final CompletedLessonRepository completedLessons;
    private final LessonVisitRepository lessonVisits;
    private final Clock clock;

    public LearningService(EnrollmentService enrollments, LessonRepository lessons,
                           CompletedLessonRepository completedLessons, LessonVisitRepository lessonVisits,
                           Clock clock) {
        this.enrollments = enrollments;
        this.lessons = lessons;
        this.completedLessons = completedLessons;
        this.lessonVisits = lessonVisits;
        this.clock = clock;
    }

    /**
     * "Meus cursos": the Student's active Enrollments, the most recently visited Course first, then the ones never
     * visited, oldest Enrollment first; and the Course to highlight. Every Course's outline, marks and last visit come
     * in one query each, whatever the number of Enrollments.
     */
    @Transactional(readOnly = true)
    public StudentEnrollmentList enrollments(long studentId) {
        List<CourseEntity> enrolled = enrollments.activeEnrollmentsOf(studentId).stream()
                .map(EnrollmentEntity::getCourse)
                .toList();
        Map<Long, Learning> learning = learningIn(studentId, enrolled);
        List<Learning> ordered = enrolled.stream()
                .map(course -> learning.getOrDefault(course.getId(), Learning.none(course)))
                .sorted(BY_LAST_VISIT)
                .toList();
        Long highlighted = ordered.stream()
                .filter(Learning::isHighlightable)
                .findFirst()
                .map(found -> found.course().getId())
                .orElse(null);
        return new StudentEnrollmentList(ordered.stream()
                .map(found -> new StudentEnrollment(courseView(found.course()), found.progress(),
                        found.resumeLesson()))
                .toList(), highlighted);
    }

    /**
     * The Student's active Enrollment in the Course, with the Lessons they completed. Without one, the Course answers
     * like an unknown one, ended Enrollments included; so does an id of any shape.
     */
    @Transactional(readOnly = true)
    public StudentEnrollmentDetail enrollment(long studentId, String courseId) {
        CourseEntity course = PathIds.parse(courseId)
                .flatMap(id -> enrollments.activeEnrollment(studentId, id))
                .map(EnrollmentEntity::getCourse)
                .orElseThrow(EnrollmentNotFoundException::new);
        Learning learning = learningIn(studentId, List.of(course)).getOrDefault(course.getId(), Learning.none(course));
        return new StudentEnrollmentDetail(courseView(course), learning.progress(), learning.resumeLesson(),
                learning.completedLessonIds());
    }

    /** Marks the Lesson as completed; marking it again changes nothing. */
    @Transactional
    public void complete(long studentId, String lessonId) {
        LessonEntity lesson = markable(studentId, lessonId);
        completedLessons.insertIfAbsent(studentId, lesson.getId(), now());
    }

    /** Takes the Lesson's mark back; taking back a mark it doesn't have changes nothing. */
    @Transactional
    public void uncomplete(long studentId, String lessonId) {
        LessonEntity lesson = markable(studentId, lessonId);
        completedLessons.deleteByStudentIdAndLessonId(studentId, lesson.getId());
    }

    /**
     * Records the Lesson as the last one the Student opened in its Course, in place of the one before: the Lesson's
     * page calls this when it mounts, and no read ever does. The same Lessons take a visit as take a mark.
     */
    @Transactional
    public void visit(long studentId, String lessonId) {
        LessonEntity lesson = markable(studentId, lessonId);
        lessonVisits.record(studentId, lesson.getCourse().getId(), lesson.getId(), now());
    }

    /** Takes the id as the path carries it, so that an id of any shape answers like an unknown one. */
    private LessonEntity markable(long studentId, String lessonId) {
        return PathIds.parse(lessonId).map(id -> markable(studentId, id)).orElseThrow(LessonNotFoundException::new);
    }

    /**
     * A published Lesson of an On sale Course, in which the Student has an active Enrollment: an "Em breve" Lesson, or
     * one whose Course is not On sale, answers like an unknown one.
     */
    private LessonEntity markable(long studentId, long lessonId) {
        LessonEntity lesson = lessons.findPublishedInOnSaleCourse(lessonId)
                .orElseThrow(LessonNotFoundException::new);
        if (!enrollments.isActivelyEnrolled(studentId, lesson.getCourse().getId())) {
            throw new EnrollmentRequiredException();
        }
        return lesson;
    }

    /**
     * What the Student has done in each of the On sale Courses, by the Course's id, in three queries whatever their
     * number. Each has a published Lesson at least, its Free lesson, which is never deleted.
     */
    private Map<Long, Learning> learningIn(long studentId, List<CourseEntity> courses) {
        Map<Long, CourseEntity> onSale = courses.stream()
                .filter(LearningService::hasProgress)
                .collect(Collectors.toMap(CourseEntity::getId, Function.identity()));
        if (onSale.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<LessonEntity>> outlines = lessons.findOutlinesOf(onSale.keySet()).stream()
                .collect(Collectors.groupingBy(lesson -> lesson.getCourse().getId()));
        Set<Long> completed = new HashSet<>(
                completedLessons.findLessonIdsByStudentIdAndCourseIdIn(studentId, onSale.keySet()));
        Map<Long, LastVisit> lastVisits = lessonVisits.findLastVisits(studentId, onSale.keySet()).stream()
                .collect(Collectors.toMap(LastVisit::getCourseId, Function.identity()));
        return onSale.values().stream().collect(Collectors.toMap(CourseEntity::getId, course -> Learning.of(course,
                outlines.getOrDefault(course.getId(), List.of()), completed, lastVisits.get(course.getId()))));
    }

    /** Only an On sale Course's Lessons open, so only its Enrollments have Progress. */
    private static boolean hasProgress(CourseEntity course) {
        return course.getStatus() == CourseStatus.ON_SALE;
    }

    /** Cut to the microseconds PostgreSQL keeps, so that an answer shows what every later read will. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static EnrolledCourse courseView(CourseEntity course) {
        return new EnrolledCourse(course.getId(), course.getSlug(), course.getTitle(), course.getArea(),
                course.getIcon(), course.getTone(), course.getStatus());
    }

    /**
     * What the Student has done in a Course: all null while the Course is Coming soon, and the last visit null too
     * before the first one.
     */
    private record Learning(CourseEntity course, Progress progress, ResumeLesson resumeLesson,
                            List<Long> completedLessonIds, Instant lastVisitedAt) {

        static Learning none(CourseEntity course) {
            return new Learning(course, null, null, null, null);
        }

        /**
         * From the Course's outline, in order; the ids of the Lessons the Student completed, in this Course or any
         * other; and the last visit, or null.
         */
        static Learning of(CourseEntity course, List<LessonEntity> outline, Set<Long> completedIds,
                           LastVisit lastVisit) {
            List<Long> completed = outline.stream().map(LessonEntity::getId).filter(completedIds::contains).sorted()
                    .toList();
            int published = Math.toIntExact(outline.stream().filter(LessonEntity::isPublished).count());
            Progress progress = ProgressCounts.of(completed.size(), published, outline.size());
            List<OutlineLesson> rulesOutline = outline.stream()
                    .map(lesson -> new OutlineLesson(lesson.getId(), lesson.isPublished()))
                    .toList();
            ResumeLesson resumeLesson = ResumeLessons
                    .indexIn(rulesOutline, completedIds, lastVisit == null ? null : lastVisit.getLessonId())
                    .stream()
                    .mapToObj(index -> resumeLessonOf(outline.get(index), index + 1))
                    .findFirst()
                    .orElse(null);
            return new Learning(course, progress, resumeLesson, completed,
                    lastVisit == null ? null : lastVisit.getVisitedAt());
        }

        /** Visited, with a published Lesson left to complete: Caught up and Finished Courses have none. */
        boolean isHighlightable() {
            return lastVisitedAt != null && progress.completed() < progress.published();
        }

        /** Numbered as the Syllabus numbers it, across the Modules, "Em breve" Lessons counted. */
        private static ResumeLesson resumeLessonOf(LessonEntity lesson, int number) {
            return new ResumeLesson(lesson.getId(), lesson.getSlug(), number, lesson.getTitle());
        }
    }
}
