package com.devlabs.aulaflix.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.ModuleEntity;

public interface ModuleRepository extends JpaRepository<ModuleEntity, Long> {

    List<ModuleEntity> findByCourseIdOrderByPosition(long courseId);

    /** The id of the Module's Course, read without loading the Module. */
    @Query("select m.course.id from ModuleEntity m where m.id = :id")
    Optional<Long> findCourseIdById(@Param("id") long id);

    /** The position after the Course's last Module, or 0 while it has none. */
    @Query("select coalesce(max(m.position) + 1, 0) from ModuleEntity m where m.course.id = :courseId")
    int findNextPositionByCourseId(@Param("courseId") long courseId);
}
