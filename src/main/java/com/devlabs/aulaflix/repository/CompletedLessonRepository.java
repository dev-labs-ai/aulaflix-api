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

    /** The Lessons of the Courses the Student completed, by id, ascending, all in one query. */
    @Query("""
            select c.lesson.id from CompletedLessonEntity c
            where c.student.id = :studentId and c.lesson.course.id in :courseIds
            order by c.lesson.id""")
    List<Long> findLessonIdsByStudentIdAndCourseIdIn(@Param("studentId") long studentId,
                                                     @Param("courseIds") Collection<Long> courseIds);
}
