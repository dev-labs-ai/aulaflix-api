package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.CourseStatus;
import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.OrderEntity;
import com.devlabs.aulaflix.dto.Order;
import com.devlabs.aulaflix.exception.AlreadyEnrolledException;
import com.devlabs.aulaflix.exception.CourseNotForSaleException;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.CourseRepository;
import com.devlabs.aulaflix.repository.OrderRepository;

/**
 * The transactions around the Asaas calls of placing an Order, each short, since Asaas is never called inside one.
 * Opening holds the Student's row lock, like every Enrollment grant, so that two placements of one Student go one at a
 * time, and the second sees the first's Order or Enrollment.
 */
@Component
class OrderPlacements {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacements.class);

    private static final Duration PIX_LIFETIME = Duration.ofMinutes(30);

    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final CourseRepository courses;
    private final EnrollmentService enrollments;
    private final Clock clock;

    OrderPlacements(OrderRepository orders, AccountRepository accounts, CourseRepository courses,
                    EnrollmentService enrollments, Clock clock) {
        this.orders = orders;
        this.accounts = accounts;
        this.courses = courses;
        this.enrollments = enrollments;
        this.clock = clock;
    }

    /**
     * Writes a new Pix Order awaiting payment, at the Course's current Pix price, or finds the one already awaiting. A
     * Student without an Asaas customer must send a valid CPF, which is checked here and kept only in the answer.
     */
    @Transactional
    Placement open(long studentId, long courseId, String cpf) {
        AccountEntity student = accounts.findLockedById(studentId).orElseThrow();
        CourseEntity course = courses.findById(courseId)
                .filter(found -> found.getStatus() == CourseStatus.ON_SALE)
                .orElseThrow(CourseNotForSaleException::new);
        if (enrollments.isActivelyEnrolled(studentId, courseId)) {
            throw new AlreadyEnrolledException();
        }
        Optional<OrderEntity> awaiting =
                orders.findByStudentAndCourseInStatus(studentId, courseId, OrderStatus.AWAITING_PAYMENT);
        if (awaiting.isPresent()) {
            return Placement.ofExisting(OrderViews.withPayment(awaiting.get()));
        }
        String customerId = student.getAsaasCustomerId();
        String cpfDigits = customerId == null ? Cpf.requireValid(cpf) : null;
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int amountCents = Pricing.of(course.getPriceCents(), course.getPixDiscountPercent(),
                course.getMaxInstallments()).pixPriceCents();
        OrderEntity order = orders.save(OrderEntity.pix(OrderCodes.next(), student, course, amountCents, now,
                now.plus(PIX_LIFETIME)));
        log.info("Student {} placed Pix Order {} for Course {}", studentId, order.getCode(), courseId);
        return new Placement(order.getId(), order.getCode(), amountCents, course.getTitle(), student.getName(),
                customerId, cpfDigits, null);
    }

    /** Keeps the Student's Asaas customer, unless another placement kept one first, whose id is answered instead. */
    @Transactional
    String recordCustomer(long studentId, String customerId) {
        AccountEntity student = accounts.findLockedById(studentId).orElseThrow();
        if (student.getAsaasCustomerId() == null) {
            student.setAsaasCustomerId(customerId);
            log.info("Student {} got Asaas customer {}", studentId, customerId);
        }
        return student.getAsaasCustomerId();
    }

    @Transactional
    Order recordPixCharge(long orderId, String chargeId, AsaasGateway.PixQrCode qrCode) {
        OrderEntity order = orders.findWithCourseById(orderId).orElseThrow();
        order.recordPixCharge(chargeId, qrCode.encodedImage(), qrCode.payload());
        return OrderViews.withPayment(order);
    }

    /** Cancels the Order after Asaas failed it, unless something else moved it on meanwhile. */
    @Transactional
    void cancel(long orderId) {
        orders.findById(orderId).orElseThrow().cancel();
    }

    /**
     * A new Order and what its Asaas calls need, or the Order that already awaited payment. The CPF is there only when
     * the Student has no Asaas customer yet.
     */
    record Placement(long orderId, String code, int amountCents, String courseTitle, String studentName,
                     String customerId, String cpf, Order existing) {

        static Placement ofExisting(Order existing) {
            return new Placement(0, existing.code(), existing.amountCents(), null, null, null, null, existing);
        }

        /** Never shows the CPF, wherever the placement ends up printed. */
        @Override
        public String toString() {
            return "Placement[orderId=" + orderId + ", code=" + code + "]";
        }
    }
}
