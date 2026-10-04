package com.devlabs.aulaflix.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.devlabs.aulaflix.domain.entity.CourseEntity;

public interface CourseRepository extends JpaRepository<CourseEntity, Long> {

    boolean existsBySlug(String slug);

    /** Holds the Course's row lock until the transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CourseEntity> findLockedById(long id);
}
