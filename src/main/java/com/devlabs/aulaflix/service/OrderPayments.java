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

    /** The events awaiting the worker, oldest first, each with its type and the charge or the Checkout it names. */
    @Transactional(readOnly = true)
    List<PendingEvent> pendingEvents() {
        return events.findByStateOrderById(WebhookEventState.PENDING).stream()
                .map(event -> new PendingEvent(event.getId(), event.getEventType(), event.getChargeId(),
                        event.getCheckoutId()))
                .toList();
    }

    /** Applies what the charge's re-read shows to its Order, and settles the event that named it, at once. */
    @Transactional
    void apply(long eventId, AsaasGateway.Charge charge) {
        settle(eventId, outcomeOf(charge, false));
    }

    /**
     * Applies what the charge's re-read shows to its Order after an event said risk analysis rejected the card, which
     * declines the Order unless the re-read shows the charge paid or still held for analysis; and settles the event.
     */
    @Transactional
    void applyRejection(long eventId, AsaasGateway.Charge charge) {
        settle(eventId, outcomeOf(charge, true));
    }

    /**
     * Applies what a job's own re-read of the charge shows to its Order, as for an event, and answers whether the
     * charge has paid the Order.
     */
    @Transactional
    boolean applyReread(AsaasGateway.Charge charge) {
        return outcomeOf(charge, false) == WebhookEventState.PROCESSED;
    }

    @Transactional
    void settle(long eventId, WebhookEventState outcome) {
        WebhookEventEntity event = events.findById(eventId).orElseThrow();
        event.settle(outcome, now());
    }

    private WebhookEventState outcomeOf(AsaasGateway.Charge charge, boolean rejectedByRiskAnalysis) {
        Optional<OrderEntity> found = lockedOrderOf(charge);
        if (found.isEmpty()) {
            log.warn("Asaas charge {} is no Order's", charge.id());
            return WebhookEventState.IGNORED;
        }
        OrderEntity order = found.get();
        if (!isUnder(order, charge)
                || BigDecimal.valueOf(order.getAmountCents(), 2).compareTo(charge.value()) != 0) {
            log.warn("Asaas charge {} does not match Order {}: it is under another reference or for another amount",
                    charge.id(), order.getCode());
            return WebhookEventState.UNPROCESSABLE;
        }
        if (!charge.deleted() && PAID.contains(charge.status())) {
            if (order.pay(now())) {
                if (order.getMethod() == PaymentMethod.CARD) {
                    order.recordCardPayment(charge.id(), charge.installment(), charge.installments());
                }
                log.info("Order {} was paid", order.getCode());
                grant(order);
            }
            return WebhookEventState.PROCESSED;
        }
        if (rejectedByRiskAnalysis && !charge.awaitingRiskAnalysis()) {
            if (order.decline()) {
                log.info("Order {} was declined: Asaas's risk analysis rejected the card", order.getCode());
            }
            return WebhookEventState.PROCESSED;
        }
        log.info("Asaas charge {} of Order {} is {}, so not paid", charge.id(), order.getCode(),
                charge.deleted() ? "deleted" : charge.status());
        return WebhookEventState.IGNORED;
    }

    /**
     * The Order the charge is for, under its Student's row lock: a Pix's by the charge's id, which the Order keeps
     * from its placement; a card's by the Checkout its payer paid on, since the charge is made only then.
     */
    private Optional<OrderEntity> lockedOrderOf(AsaasGateway.Charge charge) {
        Optional<Long> byCharge = orders.findStudentIdByChargeId(charge.id());
        if (byCharge.isPresent()) {
            accounts.findLockedById(byCharge.get()).orElseThrow();
            return orders.findWithPartiesByChargeId(charge.id());
        }
        if (charge.checkoutSession() == null) {
            return Optional.empty();
        }
        Optional<Long> byCheckout = orders.findStudentIdByCheckoutId(charge.checkoutSession());
        if (byCheckout.isEmpty()) {
            return Optional.empty();
        }
        accounts.findLockedById(byCheckout.get()).orElseThrow();
        return orders.findWithPartiesByCheckoutId(charge.checkoutSession());
    }

    /**
     * Whether the charge was made under the Order's code; or, as Asaas may not copy a Checkout's external reference
     * onto the charges its payer makes, under none, on the Order's Checkout.
     */
    private static boolean isUnder(OrderEntity order, AsaasGateway.Charge charge) {
        if (charge.externalReference() != null) {
            return order.getCode().equals(charge.externalReference());
        }
        return order.getAsaasCheckoutId() != null && order.getAsaasCheckoutId().equals(charge.checkoutSession());
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

    /** An event the worker has yet to process, its type, and the charge, or for a Checkout event the Checkout, it names. */
    record PendingEvent(long id, String type, String chargeId, String checkoutId) {
    }
}
