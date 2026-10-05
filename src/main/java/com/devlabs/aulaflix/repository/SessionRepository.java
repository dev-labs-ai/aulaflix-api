package com.devlabs.aulaflix.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.SessionEntity;

public interface SessionRepository extends JpaRepository<SessionEntity, Long> {

    /** With its Account, whose role decides how long the session may stay idle. */
    @Query("select s from SessionEntity s join fetch s.account where s.tokenHash = :tokenHash")
    Optional<SessionEntity> findByTokenHash(@Param("tokenHash") String tokenHash);

    @Modifying
    @Query("delete from SessionEntity s where s.account.id = :accountId")
    int deleteByAccountId(@Param("accountId") long accountId);

    @Modifying
    @Query("delete from SessionEntity s where s.account.id = :accountId and s.id <> :keptId")
    int deleteByAccountIdExcept(@Param("accountId") long accountId, @Param("keptId") long keptId);
}
