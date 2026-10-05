package com.devlabs.aulaflix.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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

    /** The Order with its Course. */
    @Query("select o from OrderEntity o join fetch o.course where o.id = :id")
    Optional<OrderEntity> findWithCourseById(@Param("id") long id);
}
