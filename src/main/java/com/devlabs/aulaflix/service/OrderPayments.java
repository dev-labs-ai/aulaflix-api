package com.devlabs.aulaflix.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.CourseEntity;
import com.devlabs.aulaflix.domain.entity.OrderEntity;
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

    private final WebhookEventRepository events;
    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final EnrollmentService enrollments;
    private final EmailOutbox outbox;
    private final EmailTemplates templates;
    private final Clock clock;

    OrderPayments(WebhookEventRepository events, OrderRepository orders, AccountRepository accounts,
                  EnrollmentService enrollments, EmailOutbox outbox, EmailTemplates templates, Clock clock) {
        this.events = events;
        this.orders = orders;
        this.accounts = accounts;
        this.enrollments = enrollments;
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
     * Grants the Enrollment the Order buys, and queues the email that records it; unless the Student already has the
     * Course, which makes the payment a Duplicate payment that grants nothing.
     */
    private void grant(OrderEntity order) {
        AccountEntity student = order.getStudent();
        CourseEntity course = order.getCourse();
        if (enrollments.isActivelyEnrolled(student.getId(), course.getId())) {
            order.markDuplicatePayment();
            log.warn("Order {} is a Duplicate payment: Student {} already has Course {}", order.getCode(),
                    student.getId(), course.getId());
            return;
        }
        enrollments.grantForOrder(order);
        outbox.enqueue(templates.purchaseConfirmation(student.getEmail(), student.getName(), order.getCode(),
                order.getAmountCents(), course.getTitle(), course.getSlug()));
    }

    /** Cut to the microseconds PostgreSQL keeps, so that an answer shows what every later read will. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** An event the worker has yet to process, and the charge it names. */
    record PendingEvent(long id, String chargeId) {
    }
}
