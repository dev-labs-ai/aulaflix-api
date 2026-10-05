package com.devlabs.aulaflix.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.EmailConfirmationTokenEntity;

public interface EmailConfirmationTokenRepository extends JpaRepository<EmailConfirmationTokenEntity, Long> {

    /** With its Account, whose email the token confirms. */
    @Query("select t from EmailConfirmationTokenEntity t join fetch t.account where t.tokenHash = :tokenHash")
    Optional<EmailConfirmationTokenEntity> findByTokenHash(@Param("tokenHash") String tokenHash);

    /** The Account's latest link, whichever way it was sent. */
    Optional<EmailConfirmationTokenEntity> findFirstByAccountIdOrderByCreatedAtDesc(long accountId);

    /** The links the Student asked for again since the instant, the oldest first. */
    List<EmailConfirmationTokenEntity> findByAccountIdAndResendTrueAndCreatedAtAfterOrderByCreatedAtAsc(
            long accountId, Instant since);

    /** Voids every link of the Account still unused and not yet voided, and answers how many there were. */
    @Modifying
    @Query("""
            update EmailConfirmationTokenEntity t set t.voidedAt = :at
            where t.account.id = :accountId and t.usedAt is null and t.voidedAt is null""")
    int voidUnused(@Param("accountId") long accountId, @Param("at") Instant at);
}
