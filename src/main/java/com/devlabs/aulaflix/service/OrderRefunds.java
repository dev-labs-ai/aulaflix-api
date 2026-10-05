package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.EnrollmentEndReason;
import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.entity.AccountEntity;
import com.devlabs.aulaflix.domain.entity.OrderEntity;
import com.devlabs.aulaflix.dto.AdminOrder;
import com.devlabs.aulaflix.exception.OrderNotFoundException;
import com.devlabs.aulaflix.exception.OrderNotPaidException;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.OrderRepository;

/**
 * The transactions of a Refund, each short, since Asaas is never called inside one. Changing an Order holds its
 * Student's row lock, like every placement, payment and job, so that whatever moves one Student's Orders goes one at
 * a time.
 */
@Component
class OrderRefunds {

    private static final Logger log = LoggerFactory.getLogger(OrderRefunds.class);

    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final EnrollmentService enrollments;
    private final EmailOutbox outbox;
    private final EmailTemplates templates;
    private final AdminOrderViews views;
    private final Clock clock;

    OrderRefunds(OrderRepository orders, AccountRepository accounts, EnrollmentService enrollments,
                 EmailOutbox outbox, EmailTemplates templates, AdminOrderViews views, Clock clock) {
        this.orders = orders;
        this.accounts = accounts;
        this.enrollments = enrollments;
        this.outbox = outbox;
        this.templates = templates;
        this.views = views;
        this.clock = clock;
    }

    /**
     * What a refund of the Order with the code takes: nothing more when it is refunding or refunded already, so a
     * repeated refund calls Asaas no more; otherwise its charge, refunded only when the Order is paid.
     */
    @Transactional(readOnly = true)
    RefundStart refundable(String code) {
        OrderEntity order = orders.findWithPartiesByCode(code).orElseThrow(OrderNotFoundException::new);
        if (order.getStatus() == OrderStatus.REFUNDING || order.getStatus() == OrderStatus.REFUNDED) {
            return new RefundStart(views.view(order), order.getId(), order.getStudent().getId(),
                    order.getAsaasPaymentId());
        }
        if (order.getStatus() != OrderStatus.PAID) {
            throw new OrderNotPaidException();
        }
        return new RefundStart(null, order.getId(), order.getStudent().getId(), order.getAsaasPaymentId());
    }

    /** Records the refund Asaas took at the Admin's request, and answers the Order as it now is. */
    @Transactional
    AdminOrder requested(long adminId, long orderId, long studentId) {
        accounts.findLockedById(studentId).orElseThrow();
        OrderEntity order = orders.findWithPartiesById(orderId).orElseThrow();
        if (start(order, accounts.findById(adminId).orElseThrow())) {
            log.info("Admin {} refunded Order {}", adminId, order.getCode());
        }
        return views.view(order);
    }

    /**
     * Within the caller's transaction, which holds the Student's lock: makes the paid Order refunding, at the Admin's
     * request, or at nobody's for a refund made in the Asaas UI; ends the Enrollment its payment granted, if any; and
     * queues the Student's notice. Answers whether the Order was paid, since any other state stays.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    boolean start(OrderEntity order, AccountEntity admin) {
        if (!order.refunding(now(), admin)) {
            return false;
        }
        enrollments.endGrantedBy(order, EnrollmentEndReason.REFUND);
        AccountEntity student = order.getStudent();
        outbox.enqueue(templates.refundNotice(student.getEmail(), student.getName(), order.getCode(),
                order.getAmountCents(), order.getCourse().getTitle(), order.isDuplicatePayment()));
        return true;
    }

    /** The Orders being refunded, which reconciliation follows until Asaas reports their refund done. */
    @Transactional(readOnly = true)
    List<OrderUpkeep.DueOrder> refunding() {
        return orders.findRefunding().stream()
                .map(order -> new OrderUpkeep.DueOrder(order.getId(), order.getCode(), order.getStudent().getId(),
                        order.getAsaasPaymentId()))
                .toList();
    }

    /** Records that Asaas reported the Order's refund done, unless something moved it on meanwhile. */
    @Transactional
    void done(OrderUpkeep.DueOrder due) {
        accounts.findLockedById(due.studentId()).orElseThrow();
        if (orders.findById(due.id()).orElseThrow().refunded(now())) {
            log.info("Order {} was refunded", due.code());
        }
    }

    /** Cut to the microseconds PostgreSQL keeps, so that an answer shows what every later read will. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Where a refund starts from: the Order's view when it is refunding or refunded already, and nothing is left to
     * do; otherwise null, with the Order, its Student and the charge to refund.
     */
    record RefundStart(AdminOrder already, long orderId, long studentId, String chargeId) {
    }
}
