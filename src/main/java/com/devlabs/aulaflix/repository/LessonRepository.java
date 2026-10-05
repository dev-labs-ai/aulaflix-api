package com.devlabs.aulaflix.repository;

import java.util.Collection;
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

    /**
     * The Lesson with its Course, while anyone but an Admin may see it: published, in an On sale Course. Any other
     * Lesson exists for the Admin alone.
     */
    @Query("""
            select l from LessonEntity l join fetch l.course c
            where l.id = :id and l.publishedAt is not null
              and c.status = com.devlabs.aulaflix.domain.CourseStatus.ON_SALE""")
    Optional<LessonEntity> findPublishedInOnSaleCourse(@Param("id") long id);

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

    /**
     * How many Lessons each of the Courses has, and how many of them are published, all in one query; a Course with no
     * Lesson is left out.
     */
    @Query("""
            select l.course.id as courseId, count(l) as lessonCount, count(l.publishedAt) as publishedCount
            from LessonEntity l
            where l.course.id in :courseIds
            group by l.course.id""")
    List<CourseLessonCounts> countLessonsByCourse(@Param("courseIds") Collection<Long> courseIds);

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

    interface CourseLessonCounts {

        long getCourseId();

        long getLessonCount();

        long getPublishedCount();
    }
}
