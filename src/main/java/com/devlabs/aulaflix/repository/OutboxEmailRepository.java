package com.devlabs.aulaflix.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.OutboxEmailEntity;

public interface OutboxEmailRepository extends JpaRepository<OutboxEmailEntity, Long> {

    /** The pending emails due by now, the one due longest first, then in the order they were queued. */
    @Query("""
            select e from OutboxEmailEntity e
            where e.state = com.devlabs.aulaflix.domain.entity.OutboxEmailState.PENDING and e.nextAttemptAt <= :now
            order by e.nextAttemptAt, e.id""")
    List<OutboxEmailEntity> findDue(@Param("now") Instant now, Limit limit);
}
