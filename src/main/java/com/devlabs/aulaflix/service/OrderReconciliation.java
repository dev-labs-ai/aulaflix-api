package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The reconciliation job, so that no paying Student waits on a lost webhook: it re-reads from Asaas the charge of every
 * Order that has awaited payment longer than the delay, and applies what the re-read shows, as the webhook worker
 * would. It also deletes any charge a failed placement left at Asaas under its cancelled Order's code, once the delay
 * gives a charge whose creation timed out the time to reach Asaas. Only one run goes at a time: the scheduled job,
 * whose fixed delay never overlaps two, or a test, once, synchronously, with the job off.
 */
@Service
public class OrderReconciliation {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliation.class);

    private final OrderUpkeep upkeep;
    private final OrderPlacements placements;
    private final OrderPayments payments;
    private final AsaasGateway asaas;
    private final Clock clock;
    private final Duration delay;

    public OrderReconciliation(OrderUpkeep upkeep, OrderPlacements placements, OrderPayments payments,
                               AsaasGateway asaas, Clock clock, ReconciliationDelay delay) {
        this.upkeep = upkeep;
        this.placements = placements;
        this.payments = payments;
        this.asaas = asaas;
        this.clock = clock;
        this.delay = delay.value();
    }

    /**
     * Reconciles the Orders awaiting payment, then the cancelled ones, the oldest first. While Asaas cannot be reached
     * it stops, leaving the rest for the next run, since every call would wait out the same timeout. A call Asaas
     * refuses is logged at ERROR: an awaiting Order is read again on the next run, until it expires, and a cancelled
     * one's charges are given up on, since a search or a deletion would be refused the same way.
     */
    public void reconcile() {
        Instant before = clock.instant().minus(delay);
        try {
            upkeep.awaitingSince(before).forEach(this::reread);
            upkeep.withChargesToDeleteSince(before).forEach(this::deleteCharges);
        } catch (AsaasUnavailableException failure) {
            log.warn("Left the rest of the Orders to reconcile on the next run: {}", failure.getMessage());
        }
    }

    private void reread(OrderUpkeep.DueOrder due) {
        try {
            payments.applyReread(asaas.charge(due.chargeId()));
        } catch (AsaasRefusedException refusal) {
            log.error("Could not reconcile Order {}: {}", due.code(), refusal.getMessage());
        }
    }

    private void deleteCharges(OrderUpkeep.DueOrder due) {
        try {
            asaas.chargesUnder(due.code()).forEach(asaas::deleteCharge);
            log.info("Deleted the charges left at Asaas under cancelled Order {}", due.code());
        } catch (AsaasRefusedException refusal) {
            log.error("Gave up on the charges left at Asaas under cancelled Order {}: {}", due.code(),
                    refusal.getMessage());
        }
        placements.chargesDeleted(due.id());
    }
}
