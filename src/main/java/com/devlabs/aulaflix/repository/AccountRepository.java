package com.devlabs.aulaflix.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.Role;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    boolean existsByEmail(String email);

    Optional<AccountEntity> findByEmailAndRole(String email, Role role);

    /**
     * Holds a lock on the email until the transaction ends, so that sign-ups of one email go one at a time. The row
     * does not exist yet, so there is none to lock: native, since only PostgreSQL's advisory locks take any key.
     */
    @Query(value = "select true from pg_advisory_xact_lock(hashtextextended(:email, 0))", nativeQuery = true)
    boolean lockEmail(@Param("email") String email);
}
