package com.devlabs.aulaflix.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.devlabs.aulaflix.domain.entity.CourseEntity;

public interface CourseRepository extends JpaRepository<CourseEntity, Long> {

    boolean existsBySlug(String slug);

    Optional<CourseEntity> findBySlug(String slug);

    /**
     * Every Course but the Drafts: On sale first, then the latest move first, which is the launch of an On sale Course
     * and the announcement of a Coming soon one.
     */
    @Query("""
            select c from CourseEntity c
            where c.status <> com.devlabs.aulaflix.domain.CourseStatus.DRAFT
            order by case when c.status = com.devlabs.aulaflix.domain.CourseStatus.ON_SALE then 0 else 1 end,
                     coalesce(c.onSaleAt, c.comingSoonAt) desc,
                     c.id desc""")
    List<CourseEntity> findCatalog();

    /** Holds the Course's row lock until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CourseEntity> findLockedById(long id);
}
