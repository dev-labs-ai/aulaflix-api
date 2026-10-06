package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The reconciliation job, so that no paying Student waits on a lost webhook: it re-reads from Asaas the charge of every
 * Order that has awaited payment longer than the delay, or a card Order's Checkout's charges, and applies what the
 * re-read shows, as the webhook worker would. It follows every refunding Order until Asaas reports its refund done, and
 * re-reads every paid Order once per interval, so that money going back, by a refund made in the Asaas UI, a chargeback
 * or an upheld Pix cautionary block, ends access even when its webhook was lost. It also deletes any charge a failed
 * placement left at Asaas under its cancelled Order's code, once the delay gives a charge whose creation timed out the
 * time to reach Asaas. Only one run goes at a time: the scheduled job, whose fixed delay never overlaps two, or a
 * test, once, synchronously, with the job off.
 */
@Service
public class OrderReconciliation {

    private static final Logger log = LoggerFactory.getLogger(OrderReconciliation.class);

    private final OrderUpkeep upkeep;
    private final OrderPlacements placements;
    private final OrderPayments payments;
    private final CheckoutRereads checkouts;
    private final OrderRefunds refunds;
    private final AsaasGateway asaas;
    private final Clock clock;
    private final Duration delay;
    private final Duration paidRecheckInterval;

    public OrderReconciliation(OrderUpkeep upkeep, OrderPlacements placements, OrderPayments payments,
                               CheckoutRereads checkouts, OrderRefunds refunds, AsaasGateway asaas, Clock clock,
                               ReconciliationDelay delay, PaidRecheckInterval paidRecheckInterval) {
        this.upkeep = upkeep;
        this.placements = placements;
        this.payments = payments;
        this.checkouts = checkouts;
        this.refunds = refunds;
        this.asaas = asaas;
        this.clock = clock;
        this.delay = delay.value();
        this.paidRecheckInterval = paidRecheckInterval.value();
    }

    /**
     * Reconciles the Orders awaiting payment, then the refunding ones, then the cancelled ones, the oldest first, and
     * last the paid ones unchecked for an interval, the longest unchecked first.
     * While Asaas cannot be reached it stops, leaving the rest for the next run, since every call would wait out the
     * same timeout. A call Asaas refuses is logged at ERROR: an awaiting or refunding Order is read again on the next
     * run, a paid one on the next interval, and a cancelled one's charges are given up on, since a search or a
     * deletion would be refused the same way.
     */
    public void reconcile() {
        Instant now = clock.instant();
        Instant before = now.minus(delay);
        try {
            upkeep.awaitingSince(before).forEach(this::reread);
            refunds.refunding().forEach(this::followRefund);
            upkeep.withChargesToDeleteSince(before).forEach(this::deleteCharges);
            upkeep.paidUncheckedSince(now.minus(paidRecheckInterval)).forEach(this::recheck);
        } catch (AsaasUnavailableException failure) {
            log.warn("Left the rest of the Orders to reconcile on the next run: {}", failure.getMessage());
        }
    }

    /** A Pix's charge is re-read by its id; a card's, made only once its payer paid, through the Order's Checkout. */
    private void reread(OrderUpkeep.DueOrder due) {
        try {
            if (due.chargeId() == null) {
                checkouts.reread(due.checkoutId());
                return;
            }
            payments.applyReread(asaas.charge(due.chargeId()));
        } catch (AsaasRefusedException refusal) {
            log.error("Could not reconcile Order {}: {}", due.code(), refusal.getMessage(), refusal);
        }
    }

    /** Applies what the paid Order's charge shows now, as for its webhook, and counts the interval from now. */
    private void recheck(OrderUpkeep.DueOrder due) {
        try {
            payments.applyReread(asaas.charge(due.chargeId()));
        } catch (AsaasRefusedException refusal) {
            log.error("Could not re-read the charge of paid Order {}: {}", due.code(), refusal.getMessage(),
                    refusal);
        }
        upkeep.chargeChecked(due, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    /** A refund that is not done yet, pending or awaiting an authorization, is read again on the next run. */
    private void followRefund(OrderUpkeep.DueOrder due) {
        try {
            if (asaas.charge(due.chargeId()).refundDone()) {
                refunds.done(due);
            }
        } catch (AsaasRefusedException refusal) {
            log.error("Could not follow the refund of Order {}: {}", due.code(), refusal.getMessage(), refusal);
        }
    }

    private void deleteCharges(OrderUpkeep.DueOrder due) {
        try {
            asaas.chargesUnder(due.code()).forEach(asaas::deleteCharge);
            log.info("Deleted the charges left at Asaas under cancelled Order {}", due.code());
        } catch (AsaasRefusedException refusal) {
            log.error("Gave up on the charges left at Asaas under cancelled Order {}: {}", due.code(),
                    refusal.getMessage(), refusal);
        }
        placements.chargesDeleted(due.id());
    }
}
