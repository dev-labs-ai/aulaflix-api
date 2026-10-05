package com.devlabs.aulaflix.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.LessonVisitEntity;

public interface LessonVisitRepository extends JpaRepository<LessonVisitEntity, Long> {

    /**
     * Records the Lesson as the last one the Student opened in its Course, in place of any other: only the last visit
     * per Course is kept. Two visits at once meet at the unique constraint, and the later write wins. Each first visit
     * takes its own value of the sequence, which steps by 50 to match the entity's allocation; ids may skip, and never
     * collide.
     */
    @Modifying
    @Query(value = """
            insert into lesson_visits (id, student_id, course_id, lesson_id, visited_at)
            values (nextval('seq_lesson_visit'), :studentId, :courseId, :lessonId, :visitedAt)
            on conflict (student_id, course_id)
            do update set lesson_id = excluded.lesson_id, visited_at = excluded.visited_at""", nativeQuery = true)
    int record(@Param("studentId") long studentId, @Param("courseId") long courseId, @Param("lessonId") long lessonId,
               @Param("visitedAt") Instant visitedAt);

    /** The Student's last visit in each of the Courses, all in one query; a Course never visited is left out. */
    @Query("""
            select v.course.id as courseId, v.lesson.id as lessonId, v.visitedAt as visitedAt
            from LessonVisitEntity v
            where v.student.id = :studentId and v.course.id in :courseIds""")
    List<LastVisit> findLastVisits(@Param("studentId") long studentId, @Param("courseIds") Collection<Long> courseIds);

    interface LastVisit {

        long getCourseId();

        long getLessonId();

        Instant getVisitedAt();
    }
}
