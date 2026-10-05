package com.devlabs.aulaflix.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.EnrollmentEntity;

public interface EnrollmentRepository extends JpaRepository<EnrollmentEntity, Long> {

    boolean existsByStudentIdAndCourseIdAndEndedAtIsNull(long studentId, long courseId);

    /** Holds the Enrollment's row lock until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EnrollmentEntity> findLockedById(long id);

    /** With everything its Admin view shows, in one query. */
    @Query("""
            select e from EnrollmentEntity e
            join fetch e.student join fetch e.course left join fetch e.grantedBy left join fetch e.endedBy
            where e.id = :id""")
    Optional<EnrollmentEntity> findWithPartiesById(@Param("id") long id);

    /**
     * Newest first, each with everything its Admin view shows, in one query for the page and one for the count. A
     * filter given as null applies no condition.
     */
    @Query(value = """
            select e from EnrollmentEntity e
            join fetch e.student s join fetch e.course c left join fetch e.grantedBy left join fetch e.endedBy
            where (:email is null or s.email = :email)
              and (:courseId is null or c.id = :courseId)
              and (:active is null or (:active = true and e.endedAt is null)
                                   or (:active = false and e.endedAt is not null))
            order by e.startedAt desc, e.id desc""",
            countQuery = """
            select count(e) from EnrollmentEntity e join e.student s
            where (:email is null or s.email = :email)
              and (:courseId is null or e.course.id = :courseId)
              and (:active is null or (:active = true and e.endedAt is null)
                                   or (:active = false and e.endedAt is not null))""")
    Page<EnrollmentEntity> search(@Param("email") String email, @Param("courseId") Long courseId,
                                  @Param("active") Boolean active, Pageable pageable);
}
