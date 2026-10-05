package com.devlabs.aulaflix.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.entity.OrderEntity;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {

    /** The Student's Order for the Course in the state, of which a partial unique index allows one awaiting payment. */
    @Query("""
            select o from OrderEntity o join fetch o.course
            where o.student.id = :studentId and o.course.id = :courseId and o.status = :status""")
    Optional<OrderEntity> findByStudentAndCourseInStatus(@Param("studentId") long studentId,
                                                         @Param("courseId") long courseId,
                                                         @Param("status") OrderStatus status);

    /** The Student's Orders in these states, newest first, each with its Course. */
    @Query("""
            select o from OrderEntity o join fetch o.course
            where o.student.id = :studentId and o.status in :statuses
            order by o.createdAt desc, o.id desc""")
    List<OrderEntity> findAllOfStudentInStatuses(@Param("studentId") long studentId,
                                                 @Param("statuses") Collection<OrderStatus> statuses);

    /** The Order with the code, if the Student placed it, with its Course. */
    @Query("select o from OrderEntity o join fetch o.course where o.code = :code and o.student.id = :studentId")
    Optional<OrderEntity> findOfStudentByCode(@Param("studentId") long studentId, @Param("code") String code);

    /** The Student who placed the Order that Asaas charges under this id. */
    @Query("select o.student.id from OrderEntity o where o.asaasPaymentId = :chargeId")
    Optional<Long> findStudentIdByChargeId(@Param("chargeId") String chargeId);

    /** The Order that Asaas charges under this id, with its Student and its Course. */
    @Query("""
            select o from OrderEntity o join fetch o.student join fetch o.course
            where o.asaasPaymentId = :chargeId""")
    Optional<OrderEntity> findWithPartiesByChargeId(@Param("chargeId") String chargeId);

    /** The Orders still awaiting payment that expired by the moment given, the earliest due first. */
    @Query("""
            select o from OrderEntity o
            where o.status = com.devlabs.aulaflix.domain.OrderStatus.AWAITING_PAYMENT and o.expiresAt <= :now
            order by o.expiresAt, o.id""")
    List<OrderEntity> findAwaitingExpiredBy(@Param("now") Instant now);

    /** The Orders with a charge that have awaited payment since the moment given or before, the oldest first. */
    @Query("""
            select o from OrderEntity o
            where o.status = com.devlabs.aulaflix.domain.OrderStatus.AWAITING_PAYMENT
              and o.asaasPaymentId is not null and o.createdAt <= :before
            order by o.createdAt, o.id""")
    List<OrderEntity> findAwaitingWithChargePlacedBy(@Param("before") Instant before);

    /** The cancelled Orders that may have charges left at Asaas, placed by the moment given, the oldest first. */
    @Query("""
            select o from OrderEntity o
            where o.chargesToDelete = true and o.createdAt <= :before
            order by o.createdAt, o.id""")
    List<OrderEntity> findWithChargesToDeletePlacedBy(@Param("before") Instant before);

    /** The Order with the code, whoever placed it, with everything its Admin view shows. */
    @Query("""
            select o from OrderEntity o join fetch o.student join fetch o.course left join fetch o.refundRequestedBy
            where o.code = :code""")
    Optional<OrderEntity> findWithPartiesByCode(@Param("code") String code);

    /** The Order with everything its Admin view shows. */
    @Query("""
            select o from OrderEntity o join fetch o.student join fetch o.course left join fetch o.refundRequestedBy
            where o.id = :id""")
    Optional<OrderEntity> findWithPartiesById(@Param("id") long id);

    /**
     * The paid Orders whose charge was last re-read, or, never re-read, paid, by the moment given, the longest
     * unchecked first.
     */
    @Query("""
            select o from OrderEntity o
            where o.status = com.devlabs.aulaflix.domain.OrderStatus.PAID
              and coalesce(o.chargeCheckedAt, o.paidAt) <= :before
            order by coalesce(o.chargeCheckedAt, o.paidAt), o.id""")
    List<OrderEntity> findPaidUncheckedSince(@Param("before") Instant before);

    /** Records when reconciliation re-read the Order's charge, touching no other column. */
    @Modifying
    @Query("update OrderEntity o set o.chargeCheckedAt = :at where o.id = :id")
    void chargeChecked(@Param("id") long id, @Param("at") Instant at);

    /** The Orders being refunded, which reconciliation follows until Asaas reports their refund done. */
    @Query("""
            select o from OrderEntity o join fetch o.student
            where o.status = com.devlabs.aulaflix.domain.OrderStatus.REFUNDING
            order by o.id""")
    List<OrderEntity> findRefunding();

    /**
     * Newest first, each with everything its Admin view shows, in one query for the page and one for the count. A
     * filter given as null applies no condition.
     */
    @Query(value = """
            select o from OrderEntity o
            join fetch o.student s join fetch o.course c left join fetch o.refundRequestedBy
            where (:status is null or o.status = :status)
              and (:courseId is null or c.id = :courseId)
              and (:email is null or s.email = :email)
              and (:duplicatePayment is null or o.duplicatePayment = :duplicatePayment)
            order by o.createdAt desc, o.id desc""",
            countQuery = """
            select count(o) from OrderEntity o join o.student s
            where (:status is null or o.status = :status)
              and (:courseId is null or o.course.id = :courseId)
              and (:email is null or s.email = :email)
              and (:duplicatePayment is null or o.duplicatePayment = :duplicatePayment)""")
    Page<OrderEntity> search(@Param("status") OrderStatus status, @Param("courseId") Long courseId,
                             @Param("email") String email, @Param("duplicatePayment") Boolean duplicatePayment,
                             Pageable pageable);

    /** The Order with its Course. */
    @Query("select o from OrderEntity o join fetch o.course where o.id = :id")
    Optional<OrderEntity> findWithCourseById(@Param("id") long id);
}
