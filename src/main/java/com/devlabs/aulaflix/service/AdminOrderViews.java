package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import com.devlabs.aulaflix.domain.EnrollmentStatus;
import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.EnrollmentEntity;
import com.devlabs.aulaflix.domain.entity.OrderEntity;
import com.devlabs.aulaflix.dto.AccountSummary;
import com.devlabs.aulaflix.dto.AdminOrder;
import com.devlabs.aulaflix.dto.GrantedEnrollment;
import com.devlabs.aulaflix.dto.OrderedCourse;
import com.devlabs.aulaflix.dto.PageResponse;
import com.devlabs.aulaflix.repository.EnrollmentRepository;

/**
 * Orders as Admins see them, mapped by hand within the caller's transaction, each with the Enrollment its payment
 * granted. The Student, the Course and the Admin who asked for the refund must be loaded. The days since payment
 * follow the clock.
 */
@Component
class AdminOrderViews {

    private final EnrollmentRepository enrollments;
    private final Clock clock;

    AdminOrderViews(EnrollmentRepository enrollments, Clock clock) {
        this.enrollments = enrollments;
        this.clock = clock;
    }

    AdminOrder view(OrderEntity order) {
        return view(order, enrollments.findByOrderId(order.getId()).orElse(null), clock.instant());
    }

    /** One page, with the Enrollments its Orders granted read in one query. */
    PageResponse<AdminOrder> page(Page<OrderEntity> found) {
        List<Long> ids = found.getContent().stream().map(OrderEntity::getId).toList();
        Map<Long, EnrollmentEntity> granted = ids.isEmpty() ? Map.of()
                : enrollments.findByOrderIdIn(ids).stream()
                        .collect(Collectors.toMap(enrollment -> enrollment.getOrder().getId(), Function.identity()));
        Instant now = clock.instant();
        return new PageResponse<>(found.getContent().stream()
                .map(order -> view(order, granted.get(order.getId()), now))
                .toList(), found.getNumber(), found.getSize(), found.getTotalElements(), found.getTotalPages());
    }

    private static AdminOrder view(OrderEntity order, EnrollmentEntity enrollment, Instant now) {
        CourseEntity course = order.getCourse();
        return new AdminOrder(
                order.getCode(),
                order.getStatus(),
                order.getMethod(),
                new OrderedCourse(course.getId(), course.getSlug(), course.getTitle()),
                order.getAmountCents(),
                order.getCreatedAt(),
                order.getPaidAt(),
                order.isDuplicatePayment(),
                summaryOf(order.getStudent()),
                order.getListPriceCents(),
                order.getPixDiscountPercent(),
                order.getPaidAt() == null ? null : Duration.between(order.getPaidAt(), now).toDays(),
                enrollment == null ? null : grantedView(enrollment),
                order.getAsaasPaymentId(),
                order.getRefundRequestedAt(),
                summaryOf(order.getRefundRequestedBy()),
                order.getRefundedAt());
    }

    private static GrantedEnrollment grantedView(EnrollmentEntity enrollment) {
        return new GrantedEnrollment(enrollment.getId(),
                enrollment.isActive() ? EnrollmentStatus.ACTIVE : EnrollmentStatus.ENDED,
                enrollment.getStartedAt(), enrollment.getEndedAt(), enrollment.getEndReason());
    }

    private static AccountSummary summaryOf(AccountEntity account) {
        return account == null ? null : new AccountSummary(account.getId(), account.getEmail(), account.getName());
    }
}
