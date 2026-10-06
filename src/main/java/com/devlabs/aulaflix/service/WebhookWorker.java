package com.devlabs.aulaflix.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.devlabs.aulaflix.domain.entity.WebhookEventState;

/**
 * The webhook worker: for each event the inbox stored, it re-reads the charge from Asaas with the API's own key, and
 * acts on that answer, never on the event's body, which anyone holding the token could have written. Only one run goes
 * at a time: the scheduled job, whose fixed delay never overlaps two, or a test, once, synchronously, with the job off.
 */
@Service
public class WebhookWorker {

    /** The event of a card Asaas's manual risk analysis rejected, which declines its Order once a re-read agrees. */
    static final String RISK_ANALYSIS_REJECTION = "PAYMENT_REPROVED_BY_RISK_ANALYSIS";

    private static final Logger log = LoggerFactory.getLogger(WebhookWorker.class);

    private final OrderPayments payments;
    private final OrderUpkeep upkeep;
    private final CheckoutRereads checkouts;
    private final AsaasGateway asaas;

    public WebhookWorker(OrderPayments payments, OrderUpkeep upkeep, CheckoutRereads checkouts, AsaasGateway asaas) {
        this.payments = payments;
        this.upkeep = upkeep;
        this.checkouts = checkouts;
        this.asaas = asaas;
    }

    /**
     * Processes every pending event, oldest first. While Asaas cannot be reached it stops, leaving the rest pending
     * for the next run, since every re-read would wait out the same timeout; a re-read Asaas refuses settles the
     * event as unprocessable, since a retry would be refused the same way.
     */
    public void processPending() {
        for (OrderPayments.PendingEvent event : payments.pendingEvents()) {
            try {
                process(event);
            } catch (AsaasUnavailableException failure) {
                log.warn("Left webhook event {} and the rest pending: {}", event.id(), failure.getMessage());
                return;
            } catch (AsaasRefusedException refusal) {
                log.error("Webhook event {} is unprocessable: {}", event.id(), refusal.getMessage(), refusal);
                payments.settle(event.id(), WebhookEventState.UNPROCESSABLE);
            }
        }
    }

    private void process(OrderPayments.PendingEvent event) {
        if (event.checkoutId() != null) {
            expireCheckout(event);
            return;
        }
        AsaasGateway.Charge charge = asaas.charge(event.chargeId());
        if (RISK_ANALYSIS_REJECTION.equals(event.type())) {
            payments.applyRejection(event.id(), charge);
        } else {
            payments.apply(event.id(), charge);
        }
    }

    /**
     * A Checkout expired at Asaas, the event says, which no read of the Checkout can confirm: its charges are re-read
     * first, and a payment wins, and a card held for risk analysis keeps the Order awaiting payment. Otherwise the
     * Order expires, as the expiry job would have, an Order nobody paid at the moment a forged event claims at worst.
     */
    private void expireCheckout(OrderPayments.PendingEvent event) {
        Optional<OrderUpkeep.DueOrder> due = upkeep.awaitingOnCheckout(event.checkoutId());
        if (due.isEmpty()) {
            log.info("Asaas Checkout {} has no Order awaiting payment", event.checkoutId());
            payments.settle(event.id(), WebhookEventState.IGNORED);
            return;
        }
        if (checkouts.reread(event.checkoutId()) == CheckoutRereads.Outcome.UNPAID) {
            upkeep.expire(due.get());
        }
        payments.settle(event.id(), WebhookEventState.PROCESSED);
    }
}
