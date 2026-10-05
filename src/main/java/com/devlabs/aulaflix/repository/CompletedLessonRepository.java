package com.devlabs.aulaflix.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.CompletedLessonEntity;

public interface CompletedLessonRepository extends JpaRepository<CompletedLessonEntity, Long> {

    /**
     * Marks the Lesson as completed by the Student, unless it already is: the first mark stays as it was. Two marks
     * at once meet at the unique constraint, so neither fails. Each mark takes its own value of the sequence, which
     * steps by 50 to match the entity's allocation; ids may skip, and never collide.
     */
    @Modifying
    @Query(value = """
            insert into completed_lessons (id, student_id, lesson_id, completed_at)
            values (nextval('seq_completed_lesson'), :studentId, :lessonId, :completedAt)
            on conflict (student_id, lesson_id) do nothing""", nativeQuery = true)
    int insertIfAbsent(@Param("studentId") long studentId, @Param("lessonId") long lessonId,
                       @Param("completedAt") Instant completedAt);

    @Modifying
    @Query("delete from CompletedLessonEntity c where c.student.id = :studentId and c.lesson.id = :lessonId")
    int deleteByStudentIdAndLessonId(@Param("studentId") long studentId, @Param("lessonId") long lessonId);

    /** The Lessons of the Course the Student completed, by id. */
    @Query("""
            select c.lesson.id from CompletedLessonEntity c
            where c.student.id = :studentId and c.lesson.course.id = :courseId
            order by c.lesson.id""")
    List<Long> findLessonIdsByStudentIdAndCourseId(@Param("studentId") long studentId,
                                                   @Param("courseId") long courseId);

    /** How many Lessons of each Course the Student completed, all in one query; a Course with none is left out. */
    @Query("""
            select c.lesson.course.id as courseId, count(c) as completed from CompletedLessonEntity c
            where c.student.id = :studentId and c.lesson.course.id in :courseIds
            group by c.lesson.course.id""")
    List<CourseCompletedCount> countByCourse(@Param("studentId") long studentId,
                                             @Param("courseIds") Collection<Long> courseIds);

    interface CourseCompletedCount {

        long getCourseId();

        long getCompleted();
    }
}
