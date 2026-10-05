package com.devlabs.aulaflix.service;

import org.springframework.stereotype.Component;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.PaymentMethod;
import com.devlabs.aulaflix.dto.Order;
import com.devlabs.aulaflix.exception.OrderNotAwaitingPaymentException;
import com.devlabs.aulaflix.exception.PaymentProviderErrorException;
import com.devlabs.aulaflix.exception.PaymentUnavailableException;

/**
 * Cancelling an Order awaiting payment, at the Student's request or because they chose the other method: first at
 * Asaas, its Pix charge deleted or its Checkout cancelled, so that nothing is left there to pay, and only then the
 * Order. When Asaas fails, the Order stays awaiting payment. When Asaas refuses, the charge or the Checkout's charges
 * are re-read, and a payment wins: Asaas deletes no paid charge, and the Student may have paid before the webhook came.
 * A payment that lands after the cancellation still wins too, since it pays a cancelled Order.
 */
@Component
class OrderCancellations {

    private final CancellableOrders orders;
    private final AsaasGateway asaas;
    private final OrderPayments payments;
    private final CheckoutRereads checkouts;

    OrderCancellations(CancellableOrders orders, AsaasGateway asaas, OrderPayments payments,
                       CheckoutRereads checkouts) {
        this.orders = orders;
        this.asaas = asaas;
        this.payments = payments;
        this.checkouts = checkouts;
    }

    /** Cancels the Student's Order, or answers it again once cancelled; any other state than awaiting is refused. */
    Order cancel(long studentId, String code) {
        CancellableOrders.Target target = orders.find(studentId, code);
        if (target.status() == OrderStatus.CANCELLED) {
            return target.order();
        }
        if (target.status() != OrderStatus.AWAITING_PAYMENT) {
            throw new OrderNotAwaitingPaymentException();
        }
        Order cancelled = withdraw(target);
        if (cancelled.status() != OrderStatus.CANCELLED) {
            throw new OrderNotAwaitingPaymentException();
        }
        return cancelled;
    }

    /**
     * Cancels the Student's Order awaiting payment by one method, since they chose the other; one that something moved
     * on meanwhile is left as it is, and so is one Asaas shows paid, which the new placement then finds.
     */
    void replace(long studentId, String code) {
        CancellableOrders.Target target = orders.find(studentId, code);
        if (target.status() == OrderStatus.AWAITING_PAYMENT) {
            withdraw(target);
        }
    }

    /**
     * Stops at Asaas whatever could pay the Order, then cancels it, and answers it as it is now: paid, when a re-read
     * found the payment, since cancelling leaves a paid Order as it is.
     */
    private Order withdraw(CancellableOrders.Target target) {
        try {
            stopAtAsaas(target);
        } catch (AsaasUnavailableException failure) {
            throw new PaymentUnavailableException("Order %s left awaiting payment; %s".formatted(target.code(),
                    failure.getMessage()), failure.retryAfter());
        } catch (AsaasRefusedException refusal) {
            throw new PaymentProviderErrorException("Order %s left awaiting payment; %s".formatted(target.code(),
                    refusal.getMessage()));
        }
        return orders.cancel(target);
    }

    /**
     * Deletes the Pix charge, or cancels the Checkout. Neither exists yet while the placement that makes it is still
     * waiting for Asaas: a Checkout made then has a link no one got, and a charge is left to reconciliation, which
     * searches the Order's code.
     */
    private void stopAtAsaas(CancellableOrders.Target target) {
        if (target.method() == PaymentMethod.CARD) {
            if (target.checkoutId() != null) {
                cancelCheckout(target.checkoutId());
            }
        } else if (target.chargeId() != null) {
            deleteCharge(target.chargeId());
        }
    }

    /** A refusal stands unless a re-read shows the charge deleted already, or paid, which pays the Order. */
    private void deleteCharge(String chargeId) {
        try {
            asaas.deleteCharge(chargeId);
        } catch (AsaasRefusedException refusal) {
            AsaasGateway.Charge charge = asaas.charge(chargeId);
            if (!payments.applyReread(charge) && !charge.deleted()) {
                throw refusal;
            }
        }
    }

    /**
     * A refusal stands while a card on the Checkout is held for Asaas's risk analysis. Otherwise the re-read either
     * paid the Order, or found no charge: nobody paid on the Checkout, and Asaas refuses to cancel one that expired or
     * was cancelled already, as the Student may have done on its page.
     */
    private void cancelCheckout(String checkoutId) {
        try {
            asaas.cancelCheckout(checkoutId);
        } catch (AsaasRefusedException refusal) {
            if (checkouts.reread(checkoutId) == CheckoutRereads.Outcome.HELD_FOR_RISK_ANALYSIS) {
                throw refusal;
            }
        }
    }
}
