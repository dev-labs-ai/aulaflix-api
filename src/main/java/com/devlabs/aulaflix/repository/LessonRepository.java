package com.devlabs.aulaflix.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.LessonEntity;

public interface LessonRepository extends JpaRepository<LessonEntity, Long> {

    boolean existsByModuleId(long moduleId);

    boolean existsByCourseIdAndSlug(long courseId, String slug);

    @Query("""
            select count(l) > 0 from LessonEntity l
            where l.id = :id and l.course.id = :courseId and l.publishedAt is not null""")
    boolean isPublishedLessonOf(@Param("id") long id, @Param("courseId") long courseId);

    /** In order within each Module. */
    List<LessonEntity> findByCourseIdOrderByPosition(long courseId);

    @Query("select l.id from LessonEntity l where l.course.id = :courseId")
    List<Long> findIdsByCourseId(@Param("courseId") long courseId);

    /** How many Lessons each On sale Course has, all in one query. */
    @Query("""
            select l.course.id as courseId, count(l) as lessonCount from LessonEntity l
            where l.course.status = com.devlabs.aulaflix.domain.CourseStatus.ON_SALE
            group by l.course.id""")
    List<CourseLessonCount> countLessonsOfOnSaleCourses();

    /** The id of the Lesson's Course, read without loading the Lesson. */
    @Query("select l.course.id from LessonEntity l where l.id = :id")
    Optional<Long> findCourseIdById(@Param("id") long id);

    /** The position after the Module's last Lesson, or 0 while it has none. */
    @Query("select coalesce(max(l.position) + 1, 0) from LessonEntity l where l.module.id = :moduleId")
    int findNextPositionByModuleId(@Param("moduleId") long moduleId);

    interface CourseLessonCount {

        long getCourseId();

        long getLessonCount();
    }
}
