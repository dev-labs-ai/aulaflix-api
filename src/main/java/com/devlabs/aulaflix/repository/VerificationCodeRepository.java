package com.devlabs.aulaflix.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.entity.VerificationCodeEntity;
import com.devlabs.aulaflix.domain.entity.VerificationCodeKind;

public interface VerificationCodeRepository extends JpaRepository<VerificationCodeEntity, Long> {

    /** The Account's latest code of the kind, alive or not. */
    Optional<VerificationCodeEntity> findFirstByAccountIdAndKindOrderByCreatedAtDesc(long accountId,
                                                                                     VerificationCodeKind kind);

    /** The Account's codes of every kind issued since the instant, the oldest first. */
    List<VerificationCodeEntity> findByAccountIdAndCreatedAtAfterOrderByCreatedAtAsc(long accountId, Instant since);

    /** Voids every code of the kind the Account has neither used nor lost yet, and answers how many there were. */
    @Modifying
    @Query("""
            update VerificationCodeEntity c set c.voidedAt = :at
            where c.account.id = :accountId and c.kind = :kind and c.usedAt is null and c.voidedAt is null""")
    int voidUnused(@Param("accountId") long accountId, @Param("kind") VerificationCodeKind kind,
                   @Param("at") Instant at);
}
