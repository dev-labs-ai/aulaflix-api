package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
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
import com.devlabs.aulaflix.dto.StudentEnrollment;
import com.devlabs.aulaflix.dto.StudentEnrollmentDetail;
import com.devlabs.aulaflix.dto.StudentEnrollmentList;
import com.devlabs.aulaflix.exception.EnrollmentNotFoundException;
import com.devlabs.aulaflix.exception.EnrollmentRequiredException;
import com.devlabs.aulaflix.exception.LessonNotFoundException;
import com.devlabs.aulaflix.repository.CompletedLessonRepository;
import com.devlabs.aulaflix.repository.CompletedLessonRepository.CourseCompletedCount;
import com.devlabs.aulaflix.repository.LessonRepository;
import com.devlabs.aulaflix.repository.LessonRepository.CourseLessonCounts;

/**
 * The Learning module: the Lessons a Student marks as completed, and the Progress counted from them. Progress belongs
 * to the Student, not to an Enrollment, but is reached only through an active one, so it comes back intact with a new
 * Enrollment. A Coming soon Course has no Progress until its launch.
 */
@Service
public class LearningService {

    private final EnrollmentService enrollments;
    private final LessonRepository lessons;
    private final CompletedLessonRepository completedLessons;
    private final Clock clock;

    public LearningService(EnrollmentService enrollments, LessonRepository lessons,
                           CompletedLessonRepository completedLessons, Clock clock) {
        this.enrollments = enrollments;
        this.lessons = lessons;
        this.completedLessons = completedLessons;
        this.clock = clock;
    }

    /**
     * "Meus cursos": the Student's active Enrollments, oldest first. The counts of every Course come in one query each,
     * whatever the number of Enrollments.
     */
    @Transactional(readOnly = true)
    public StudentEnrollmentList enrollments(long studentId) {
        List<CourseEntity> enrolled = enrollments.activeEnrollmentsOf(studentId).stream()
                .map(EnrollmentEntity::getCourse)
                .toList();
        Map<Long, Progress> progress = progressOf(studentId, enrolled.stream()
                .filter(LearningService::hasProgress)
                .map(CourseEntity::getId)
                .toList());
        return new StudentEnrollmentList(enrolled.stream()
                .map(course -> new StudentEnrollment(courseView(course), progress.get(course.getId())))
                .toList());
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
        if (!hasProgress(course)) {
            return new StudentEnrollmentDetail(courseView(course), null, null);
        }
        List<Long> completed = completedLessons.findLessonIdsByStudentIdAndCourseId(studentId, course.getId());
        return new StudentEnrollmentDetail(courseView(course),
                progressOf(studentId, List.of(course.getId())).get(course.getId()), completed);
    }

    /** Marks the Lesson as completed; marking it again changes nothing. */
    @Transactional
    public void complete(long studentId, String lessonId) {
        LessonEntity lesson = markable(studentId, lessonId);
        completedLessons.insertIfAbsent(studentId, lesson.getId(), clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** Takes the Lesson's mark back; taking back a mark it doesn't have changes nothing. */
    @Transactional
    public void uncomplete(long studentId, String lessonId) {
        LessonEntity lesson = markable(studentId, lessonId);
        completedLessons.deleteByStudentIdAndLessonId(studentId, lesson.getId());
    }

    /**
     * A published Lesson of an On sale Course, in which the Student has an active Enrollment: an "Em breve" Lesson, or
     * one whose Course is not On sale, answers like an unknown one.
     */
    private LessonEntity markable(long studentId, String lessonId) {
        LessonEntity lesson = PathIds.parse(lessonId).flatMap(lessons::findPublishedInOnSaleCourse)
                .orElseThrow(LessonNotFoundException::new);
        if (!enrollments.isActivelyEnrolled(studentId, lesson.getCourse().getId())) {
            throw new EnrollmentRequiredException();
        }
        return lesson;
    }

    /**
     * The Progress in each of the On sale Courses, by the Course's id, in two queries whatever their number. Each has a
     * Lesson at least, its Free lesson, which is never deleted.
     */
    private Map<Long, Progress> progressOf(long studentId, List<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, CourseLessonCounts> lessonCounts = lessons.countLessonsByCourse(courseIds).stream()
                .collect(Collectors.toMap(CourseLessonCounts::getCourseId, Function.identity()));
        Map<Long, Long> completed = completedLessons.countByCourse(studentId, courseIds).stream()
                .collect(Collectors.toMap(CourseCompletedCount::getCourseId, CourseCompletedCount::getCompleted));
        return courseIds.stream().collect(Collectors.toMap(Function.identity(), id -> ProgressCounts.of(
                Math.toIntExact(completed.getOrDefault(id, 0L)),
                Math.toIntExact(lessonCounts.get(id).getPublishedCount()),
                Math.toIntExact(lessonCounts.get(id).getLessonCount()))));
    }

    /** Only an On sale Course's Lessons open, so only its Enrollments have Progress. */
    private static boolean hasProgress(CourseEntity course) {
        return course.getStatus() == CourseStatus.ON_SALE;
    }

    private static EnrolledCourse courseView(CourseEntity course) {
        return new EnrolledCourse(course.getId(), course.getSlug(), course.getTitle(), course.getArea(),
                course.getIcon(), course.getTone(), course.getStatus());
    }
}
