package com.devlabs.aulaflix.service;

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

    private static final Logger log = LoggerFactory.getLogger(WebhookWorker.class);

    private final OrderPayments payments;
    private final AsaasGateway asaas;

    public WebhookWorker(OrderPayments payments, AsaasGateway asaas) {
        this.payments = payments;
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
                payments.apply(event.id(), asaas.charge(event.chargeId()));
            } catch (AsaasUnavailableException failure) {
                log.warn("Left webhook event {} and the rest pending: {}", event.id(), failure.getMessage());
                return;
            } catch (AsaasRefusedException refusal) {
                log.error("Webhook event {} is unprocessable: {}", event.id(), refusal.getMessage());
                payments.settle(event.id(), WebhookEventState.UNPROCESSABLE);
            }
        }
    }
}
