package com.devlabs.aulaflix.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The expiry job: an Order still awaiting payment at its {@code expiresAt} expires, 30 minutes after it was placed for
 * a Pix. It re-reads the charge from Asaas first, and a payment wins: the Order is paid instead. Otherwise the charge
 * is deleted, so that no one pays it afterwards, and only then the Order expires, which emails no one. Only one run
 * goes at a time: the scheduled job, whose fixed delay never overlaps two, or a test, once, synchronously, with the job
 * off.
 */
@Service
public class OrderExpiry {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiry.class);

    private final OrderUpkeep upkeep;
    private final OrderPayments payments;
    private final AsaasGateway asaas;
    private final Clock clock;

    public OrderExpiry(OrderUpkeep upkeep, OrderPayments payments, AsaasGateway asaas, Clock clock) {
        this.upkeep = upkeep;
        this.payments = payments;
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
                if (paidElseDeleted(due)) {
                    continue;
                }
            } catch (AsaasUnavailableException failure) {
                log.warn("Left Order {} and the rest to expire on the next run: {}", due.code(),
                        failure.getMessage());
                return;
            } catch (AsaasRefusedException refusal) {
                log.error("Expiring Order {} without its charge: {}", due.code(), refusal.getMessage());
            }
            upkeep.expire(due);
        }
    }

    /**
     * Answers whether the re-read shows the charge paid, which pays the Order; when it does not, deletes the charge,
     * unless it is deleted already. An Order whose charge's id never came back was left by an API stopped while placing
     * it, before its QR code reached the Student, so nothing under its code was paid: every charge there goes.
     */
    private boolean paidElseDeleted(OrderUpkeep.DueOrder due) {
        if (due.chargeId() == null) {
            asaas.chargesUnder(due.code()).forEach(asaas::deleteCharge);
            return false;
        }
        AsaasGateway.Charge charge = asaas.charge(due.chargeId());
        if (payments.applyReread(charge)) {
            return true;
        }
        if (!charge.deleted()) {
            asaas.deleteCharge(due.chargeId());
        }
        return false;
    }
}
