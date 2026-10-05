package com.devlabs.aulaflix.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.EnrollmentEndReason;
import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.PaymentMethod;
import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.OrderEntity;
import com.devlabs.aulaflix.domain.entity.Role;
import com.devlabs.aulaflix.domain.entity.WebhookEventEntity;
import com.devlabs.aulaflix.domain.entity.WebhookEventState;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.OrderRepository;
import com.devlabs.aulaflix.repository.WebhookEventRepository;

/**
 * The transactions of the webhook worker, each short, since Asaas is never called inside one. Applying a charge holds
 * its Student's row lock, like every placement and every Enrollment grant, so that a payment, a placement and a grant
 * of one Student go one at a time.
 */
@Component
class OrderPayments {

    private static final Logger log = LoggerFactory.getLogger(OrderPayments.class);

    /** A charge in either status is paid; a Pix under a cautionary block is {@code CONFIRMED} until it clears. */
    private static final Set<String> PAID = Set.of("CONFIRMED", "RECEIVED");

    /** The Orders whose money the API holds, or is giving back: those money going back at Asaas moves on. */
    private static final Set<OrderStatus> MONEY_TAKEN = EnumSet.of(OrderStatus.PAID, OrderStatus.REFUNDING);

    private final WebhookEventRepository events;
    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final EnrollmentService enrollments;
    private final OrderRefunds refunds;
    private final EmailOutbox outbox;
    private final EmailTemplates templates;
    private final Clock clock;

    OrderPayments(WebhookEventRepository events, OrderRepository orders, AccountRepository accounts,
                  EnrollmentService enrollments, OrderRefunds refunds, EmailOutbox outbox, EmailTemplates templates,
                  Clock clock) {
        this.events = events;
        this.orders = orders;
        this.accounts = accounts;
        this.enrollments = enrollments;
        this.refunds = refunds;
        this.outbox = outbox;
        this.templates = templates;
        this.clock = clock;
    }

    /** The events awaiting the worker, oldest first, each with the charge it names. */
    @Transactional(readOnly = true)
    List<PendingEvent> pendingEvents() {
        return events.findByStateOrderById(WebhookEventState.PENDING).stream()
                .map(event -> new PendingEvent(event.getId(), event.getChargeId()))
                .toList();
    }

    /** Applies what the charge's re-read shows to its Order, and settles the event that named it, at once. */
    @Transactional
    void apply(long eventId, AsaasGateway.Charge charge) {
        settle(eventId, outcomeOf(charge));
    }

    /**
     * Applies what a job's own re-read of the charge shows to its Order, as for an event, and answers whether the
     * charge has paid the Order.
     */
    @Transactional
    boolean applyReread(AsaasGateway.Charge charge) {
        return outcomeOf(charge) == WebhookEventState.PROCESSED;
    }

    @Transactional
    void settle(long eventId, WebhookEventState outcome) {
        WebhookEventEntity event = events.findById(eventId).orElseThrow();
        event.settle(outcome, now());
    }

    private WebhookEventState outcomeOf(AsaasGateway.Charge charge) {
        Optional<Long> studentId = orders.findStudentIdByChargeId(charge.id());
        if (studentId.isEmpty()) {
            log.warn("Asaas charge {} is no Order's", charge.id());
            return WebhookEventState.IGNORED;
        }
        accounts.findLockedById(studentId.get()).orElseThrow();
        OrderEntity order = orders.findWithPartiesByChargeId(charge.id()).orElseThrow();
        if (!order.getCode().equals(charge.externalReference())
                || BigDecimal.valueOf(order.getAmountCents(), 2).compareTo(charge.value()) != 0) {
            log.warn("Asaas charge {} does not match Order {}: it is under another reference or for another amount",
                    charge.id(), order.getCode());
            return WebhookEventState.UNPROCESSABLE;
        }
        if (charge.moneyBack() && MONEY_TAKEN.contains(order.getStatus())) {
            followMoneyBack(order, charge);
            return WebhookEventState.PROCESSED;
        }
        if (charge.deleted() || !PAID.contains(charge.status())) {
            log.info("Asaas charge {} of Order {} is {}, so not paid", charge.id(), order.getCode(),
                    charge.deleted() ? "deleted" : charge.status());
            return WebhookEventState.IGNORED;
        }
        if (order.pay(now())) {
            log.info("Order {} was paid", order.getCode());
            grant(order);
        }
        return WebhookEventState.PROCESSED;
    }

    /**
     * Access follows the money, whoever moved it: a chargeback reverses the paid Order; a paid Pix refunded with no
     * refund of its own had its cautionary block upheld, which reverses it too; and any other refund, made by the API
     * or in the Asaas UI, refunds it. Either way the Enrollment the Order granted ends.
     */
    private void followMoneyBack(OrderEntity order, AsaasGateway.Charge charge) {
        if (charge.chargedBack()) {
            reverse(order, EnrollmentEndReason.CHARGEBACK);
        } else if (!charge.refundRequested() && order.getMethod() == PaymentMethod.PIX
                && order.getStatus() == OrderStatus.PAID) {
            reverse(order, EnrollmentEndReason.PIX_BLOCK_UPHELD);
        } else {
            refund(order, charge);
        }
    }

    /** A refund seen on the charge is a Refund: at nobody's request, when the API never asked for it. */
    private void refund(OrderEntity order, AsaasGateway.Charge charge) {
        if (refunds.start(order, null)) {
            log.info("Order {} is refunding: its charge {} shows a refund the API did not ask for", order.getCode(),
                    charge.id());
        }
        if (charge.refundDone() && order.refunded(now())) {
            log.info("Order {} was refunded", order.getCode());
        }
    }

    private void reverse(OrderEntity order, EnrollmentEndReason reason) {
        if (order.reverse()) {
            log.info("Order {} was reversed by {}", order.getCode(), reason);
            enrollments.endGrantedBy(order, reason);
        }
    }

    /**
     * Grants the Enrollment the Order buys, and queues the email that records it; unless the Student already has the
     * Course, which makes the payment a Duplicate payment that grants nothing and alerts every Admin.
     */
    private void grant(OrderEntity order) {
        AccountEntity student = order.getStudent();
        CourseEntity course = order.getCourse();
        if (enrollments.isActivelyEnrolled(student.getId(), course.getId())) {
            order.markDuplicatePayment();
            log.warn("Order {} is a Duplicate payment: Student {} already has Course {}", order.getCode(),
                    student.getId(), course.getId());
            alertAdmins(order);
            return;
        }
        enrollments.grantForOrder(order);
        outbox.enqueue(templates.purchaseConfirmation(student.getEmail(), student.getName(), order.getCode(),
                order.getAmountCents(), course.getTitle(), course.getSlug()));
    }

    /** Queues the Duplicate payment alert to every Admin, in the transaction that marks the Order. */
    private void alertAdmins(OrderEntity order) {
        for (AccountEntity admin : accounts.findByRoleOrderById(Role.ADMIN)) {
            outbox.enqueue(templates.duplicatePaymentAlert(admin.getEmail(), admin.getName(), order.getCode(),
                    order.getAmountCents(), order.getCourse().getTitle()));
        }
    }

    /** Cut to the microseconds PostgreSQL keeps, so that an answer shows what every later read will. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** An event the worker has yet to process, and the charge it names. */
    record PendingEvent(long id, String chargeId) {
    }
}
