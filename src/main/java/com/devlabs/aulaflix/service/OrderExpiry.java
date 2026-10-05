package com.devlabs.aulaflix.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The expiry job: an Order still awaiting payment at its {@code expiresAt} expires, 30 minutes after it was placed for
 * a Pix, and 60 for a card. It re-reads Asaas first, and a payment wins: the Order is paid instead. Otherwise a Pix's
 * charge is deleted, so that no one pays it afterwards, and only then the Order expires, which emails no one. A card's
 * Checkout expires at Asaas on its own; a card Asaas holds for risk analysis keeps its Order awaiting payment. Only one
 * run goes at a time: the scheduled job, whose fixed delay never overlaps two, or a test, once, synchronously, with the
 * job off.
 */
@Service
public class OrderExpiry {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiry.class);

    private final OrderUpkeep upkeep;
    private final OrderPayments payments;
    private final CheckoutRereads checkouts;
    private final AsaasGateway asaas;
    private final Clock clock;

    public OrderExpiry(OrderUpkeep upkeep, OrderPayments payments, CheckoutRereads checkouts, AsaasGateway asaas,
                       Clock clock) {
        this.upkeep = upkeep;
        this.payments = payments;
        this.checkouts = checkouts;
        this.asaas = asaas;
        this.clock = clock;
    }

    /**
     * Expires every Order due, the earliest first. While Asaas cannot be reached it stops, leaving that Order and the
     * rest awaiting payment for the next run, since every call would wait out the same timeout. A call Asaas refuses
     * is logged at ERROR, and the Order expires all the same, since a retry would be refused the same way: the charge
     * the re-read cannot find cannot be paid, and should one Asaas would not delete be paid after all, its payment
     * still wins.
     */
    public void expireDue() {
        for (OrderUpkeep.DueOrder due : upkeep.expiredBy(clock.instant())) {
            try {
                expire(due);
            } catch (AsaasUnavailableException failure) {
                log.warn("Left Order {} and the rest to expire on the next run: {}", due.code(),
                        failure.getMessage());
                return;
            }
        }
    }

    /**
     * Expires the one Order due, as a run of the job does, now that a placement found it awaiting payment past its
     * {@code expiresAt}: a payment the re-read finds wins, and a card held for risk analysis stays awaiting. Throws
     * when Asaas cannot be reached, which leaves the Order as it was.
     */
    void expire(OrderUpkeep.DueOrder due) {
        try {
            if (!leftToExpire(due)) {
                return;
            }
        } catch (AsaasRefusedException refusal) {
            log.error("Expiring Order {} without its charge: {}", due.code(), refusal.getMessage());
        }
        upkeep.expire(due);
    }

    /**
     * Applies what Asaas shows to the Order, and answers whether it is still left to expire: a card Order whose
     * Checkout's charges paid it is not, nor one Asaas holds for risk analysis.
     */
    private boolean leftToExpire(OrderUpkeep.DueOrder due) {
        if (due.checkoutId() == null) {
            payOrDeleteTheCharge(due);
            return true;
        }
        CheckoutRereads.Outcome outcome = checkouts.reread(due.checkoutId());
        if (outcome == CheckoutRereads.Outcome.HELD_FOR_RISK_ANALYSIS) {
            log.info("Left card Order {} awaiting payment past its expiry: Asaas holds it for risk analysis",
                    due.code());
        }
        return outcome == CheckoutRereads.Outcome.UNPAID;
    }

    /**
     * Pays the Order when the re-read shows its charge paid, which leaves it nothing to expire; otherwise deletes the
     * charge, unless it is deleted already. An Order whose charge's id never came back was left by an API stopped while
     * placing it, before its QR code, or its Checkout's link, reached the Student, so nothing under its code was paid:
     * every charge there goes.
     */
    private void payOrDeleteTheCharge(OrderUpkeep.DueOrder due) {
        if (due.chargeId() == null) {
            asaas.chargesUnder(due.code()).forEach(asaas::deleteCharge);
            return;
        }
        AsaasGateway.Charge charge = asaas.charge(due.chargeId());
        if (!payments.applyReread(charge) && !charge.deleted()) {
            asaas.deleteCharge(due.chargeId());
        }
    }
}
