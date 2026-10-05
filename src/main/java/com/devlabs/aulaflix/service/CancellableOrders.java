package com.devlabs.aulaflix.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.PaymentMethod;
import com.devlabs.aulaflix.domain.entity.OrderEntity;
import com.devlabs.aulaflix.dto.Order;
import com.devlabs.aulaflix.exception.OrderNotFoundException;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.OrderRepository;

/**
 * The transactions around the Asaas calls of cancelling an Order, each short, since Asaas is never called inside one.
 * Cancelling holds the Student's row lock, like every placement and every payment, so that it finds the Order as the
 * last of them left it.
 */
@Component
class CancellableOrders {

    private static final Logger log = LoggerFactory.getLogger(CancellableOrders.class);

    private final OrderRepository orders;
    private final AccountRepository accounts;

    CancellableOrders(OrderRepository orders, AccountRepository accounts) {
        this.orders = orders;
        this.accounts = accounts;
    }

    /** One of the Student's Orders, with what Asaas holds for it; another Student's code answers like an unknown one. */
    @Transactional(readOnly = true)
    Target find(long studentId, String code) {
        return orders.findOfStudentByCode(studentId, code)
                .map(order -> new Target(order.getId(), order.getCode(), studentId, order.getMethod(),
                        order.getStatus(), order.getAsaasPaymentId(), order.getAsaasCheckoutId(),
                        OrderViews.withPayment(order)))
                .orElseThrow(OrderNotFoundException::new);
    }

    /**
     * Cancels the Order once nothing at Asaas can be paid for it any more, unless something, a payment above all, moved
     * it on meanwhile; and answers it as it is now. A Pix Order whose charge's id never came back may have a charge
     * under its code all the same, which it keeps to delete.
     */
    @Transactional
    Order cancel(Target target) {
        accounts.findLockedById(target.studentId()).orElseThrow();
        OrderEntity order = orders.findWithCourseById(target.id()).orElseThrow();
        if (order.getStatus() == OrderStatus.AWAITING_PAYMENT) {
            if (target.method() == PaymentMethod.PIX && target.chargeId() == null) {
                order.cancelLeavingChargesToDelete();
            } else {
                order.cancel();
            }
            log.info("Student {} cancelled Order {}", target.studentId(), target.code());
        }
        return OrderViews.withPayment(order);
    }

    /**
     * An Order to cancel: its method and state; the id of its Pix charge, null until Asaas gave one; the id of its
     * Checkout, likewise; and the Order as its Student sees it.
     */
    record Target(long id, String code, long studentId, PaymentMethod method, OrderStatus status, String chargeId,
                  String checkoutId, Order order) {
    }
}
