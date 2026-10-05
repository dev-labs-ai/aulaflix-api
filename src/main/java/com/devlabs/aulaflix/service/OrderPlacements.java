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
import com.devlabs.aulaflix.domain.PaymentMethod;
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

    /** How long a card Order awaits payment, and its Checkout lives at Asaas. */
    static final Duration CARD_LIFETIME = Duration.ofMinutes(60);

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
     * Writes a new Order awaiting payment, or finds the one already awaiting. A Pix Order is at the Course's current
     * Pix price, and a Student without an Asaas customer must send a valid CPF for it, which is checked here and kept
     * only in the answer; it is checked before the one awaiting by card is answered too, so that a switch the CPF
     * would refuse cancels nothing. A card Order is at the Course's current price, and asks for no CPF: the Student
     * gives Asaas their details on its page.
     */
    @Transactional
    Placement open(long studentId, long courseId, PaymentMethod method, String cpf) {
        AccountEntity student = accounts.findLockedById(studentId).orElseThrow();
        CourseEntity course = courses.findById(courseId)
                .filter(found -> found.getStatus() == CourseStatus.ON_SALE)
                .orElseThrow(CourseNotForSaleException::new);
        if (enrollments.isActivelyEnrolled(studentId, courseId)) {
            throw new AlreadyEnrolledException();
        }
        Optional<OrderEntity> awaiting =
                orders.findByStudentAndCourseInStatus(studentId, courseId, OrderStatus.AWAITING_PAYMENT);
        String customerId = student.getAsaasCustomerId();
        boolean needsCpf = method == PaymentMethod.PIX && customerId == null;
        if (awaiting.isPresent()) {
            if (needsCpf && awaiting.get().getMethod() != method) {
                Cpf.requireValid(cpf);
            }
            return Placement.ofExisting(OrderViews.withPayment(awaiting.get()));
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (method == PaymentMethod.CARD) {
            OrderEntity order = orders.save(OrderEntity.card(OrderCodes.next(), student, course, now,
                    now.plus(CARD_LIFETIME)));
            log.info("Student {} placed card Order {} for Course {}", studentId, order.getCode(), courseId);
            return Placement.of(order, null, null);
        }
        String cpfDigits = needsCpf ? Cpf.requireValid(cpf) : null;
        int amountCents = Pricing.of(course.getPriceCents(), course.getPixDiscountPercent(),
                course.getMaxInstallments()).pixPriceCents();
        OrderEntity order = orders.save(OrderEntity.pix(OrderCodes.next(), student, course, amountCents, now,
                now.plus(PIX_LIFETIME)));
        log.info("Student {} placed Pix Order {} for Course {}", studentId, order.getCode(), courseId);
        return Placement.of(order, customerId, cpfDigits);
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

    @Transactional
    Order recordCheckout(long orderId, AsaasGateway.Checkout checkout) {
        OrderEntity order = orders.findWithCourseById(orderId).orElseThrow();
        order.recordCheckout(checkout.id(), checkout.link());
        return OrderViews.withPayment(order);
    }

    /**
     * Cancels the Order after Asaas failed it, unless something else moved it on meanwhile. Once a charge was asked
     * for, one may be at Asaas whatever Asaas answered, so the Order keeps it to delete until it is known to be gone.
     */
    @Transactional
    void cancel(long orderId, boolean chargeAskedFor) {
        OrderEntity order = orders.findById(orderId).orElseThrow();
        if (chargeAskedFor) {
            order.cancelLeavingChargesToDelete();
        } else {
            order.cancel();
        }
    }

    /** The cancelled Order's one charge was deleted by its id: nothing is left at Asaas to look for. */
    @Transactional
    void chargesDeleted(long orderId) {
        orders.findById(orderId).orElseThrow().chargesDeleted();
    }

    /**
     * A new Order and what its Asaas calls need, or the Order that already awaited payment. The CPF is there only when
     * the Student has no Asaas customer yet.
     */
    record Placement(long orderId, String code, PaymentMethod method, int amountCents, String courseTitle,
                     String courseSlug, int maxInstallments, String studentName, String customerId, String cpf,
                     Order existing) {

        /** The new Order, with its Course and Student loaded. */
        static Placement of(OrderEntity order, String customerId, String cpf) {
            CourseEntity course = order.getCourse();
            return new Placement(order.getId(), order.getCode(), order.getMethod(), order.getAmountCents(),
                    course.getTitle(), course.getSlug(), course.getMaxInstallments(), order.getStudent().getName(),
                    customerId, cpf, null);
        }

        static Placement ofExisting(Order existing) {
            return new Placement(0, existing.code(), existing.method(), existing.amountCents(), null, null, 0, null,
                    null, null, existing);
        }

        /** Never shows the CPF, wherever the placement ends up printed. */
        @Override
        public String toString() {
            return "Placement[orderId=" + orderId + ", code=" + code + "]";
        }
    }
}
