package com.devlabs.aulaflix.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devlabs.aulaflix.domain.entity.CourseEntity;

public interface CourseRepository extends JpaRepository<CourseEntity, Long> {

    boolean existsBySlug(String slug);
}
