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
import com.devlabs.aulaflix.dto.CoursePricing;
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
     * gives Asaas their details on its page. One awaiting payment past its {@code expiresAt} is answered as lapsed,
     * for the expiry to settle before anything else, since its QR code or Checkout is dead.
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
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<OrderEntity> awaiting =
                orders.findByStudentAndCourseInStatus(studentId, courseId, OrderStatus.AWAITING_PAYMENT);
        if (awaiting.isPresent()) {
            return existing(awaiting.get(), student, method, cpf, now);
        }
        return method == PaymentMethod.CARD ? placeCard(student, course, now) : placePix(student, course, cpf, now);
    }

    /** The Order already awaiting payment: lapsed past its {@code expiresAt}, or answered once the CPF is checked. */
    private static Placement existing(OrderEntity awaiting, AccountEntity student, PaymentMethod method, String cpf,
                                      Instant now) {
        if (!awaiting.getExpiresAt().isAfter(now)) {
            return new Placement.Lapsed(OrderUpkeep.DueOrder.of(awaiting), OrderViews.withPayment(awaiting));
        }
        if (needsCpf(student, method) && awaiting.getMethod() != method) {
            Cpf.requireValid(cpf);
        }
        return new Placement.Awaiting(OrderViews.withPayment(awaiting));
    }

    private Placement placeCard(AccountEntity student, CourseEntity course, Instant now) {
        OrderEntity order = orders.save(OrderEntity.card(OrderCodes.next(), student, course,
                Pricing.of(course).pixDiscountPercent(), now, now.plus(CARD_LIFETIME)));
        log.info("Student {} placed card Order {} for Course {}", student.getId(), order.getCode(), course.getId());
        return new Placement.NewCard(order.getId(), order.getCode(), order.getAmountCents(), course.getTitle(),
                course.getSlug(), course.getMaxInstallments());
    }

    private Placement placePix(AccountEntity student, CourseEntity course, String cpf, Instant now) {
        Placement.Payer payer = needsCpf(student, PaymentMethod.PIX)
                ? new Placement.NewCustomer(student.getName(), Cpf.requireValid(cpf))
                : new Placement.Customer(student.getAsaasCustomerId());
        CoursePricing pricing = Pricing.of(course);
        OrderEntity order = orders.save(OrderEntity.pix(OrderCodes.next(), student, course,
                pricing.pixDiscountPercent(), pricing.pixPriceCents(), now, now.plus(PIX_LIFETIME)));
        log.info("Student {} placed Pix Order {} for Course {}", student.getId(), order.getCode(), course.getId());
        return new Placement.NewPix(order.getId(), order.getCode(), order.getAmountCents(), course.getTitle(), payer);
    }

    /** A Pix needs the CPF until the Student's first one made their Asaas customer. */
    private static boolean needsCpf(AccountEntity student, PaymentMethod method) {
        return method == PaymentMethod.PIX && student.getAsaasCustomerId() == null;
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

    /** What opening a placement found: a new Order, with what its Asaas calls need, or the one already awaiting. */
    sealed interface Placement {

        /** A new card Order, with what its Checkout needs. */
        record NewCard(long orderId, String code, int amountCents, String courseTitle, String courseSlug,
                       int maxInstallments) implements Placement {
        }

        /** A new Pix Order, with what its charge needs: who pays it at Asaas. */
        record NewPix(long orderId, String code, int amountCents, String courseTitle, Payer payer)
                implements Placement {
        }

        /** The Order that already awaited payment, as its Student sees it. */
        record Awaiting(Order order) implements Existing {
        }

        /**
         * The Order that already awaited payment, past its {@code expiresAt}, which the expiry job has yet to see: it
         * stays so only while Asaas holds its card for risk analysis.
         */
        record Lapsed(OrderUpkeep.DueOrder due, Order order) implements Existing {
        }

        /** An Order that already awaited payment, as its Student sees it. */
        sealed interface Existing extends Placement {

            Order order();
        }

        /** Who pays a Pix at Asaas: the Student's customer there, or the one their first Pix makes. */
        sealed interface Payer {
        }

        /** The Student's Asaas customer, made by an earlier Pix. */
        record Customer(String id) implements Payer {
        }

        /** What makes the Student's Asaas customer: their name, and their CPF, kept only in the answer. */
        record NewCustomer(String name, String cpf) implements Payer {

            /** Never shows the CPF, wherever the placement ends up printed. */
            @Override
            public String toString() {
                return "NewCustomer[cpf=hidden]";
            }
        }
    }
}
