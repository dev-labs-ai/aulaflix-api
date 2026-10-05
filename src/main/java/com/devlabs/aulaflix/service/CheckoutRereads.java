package com.devlabs.aulaflix.service;

import org.springframework.stereotype.Component;

/**
 * What a card Order's Checkout shows at Asaas: the charges its payer made, one per installment, re-read one by one and
 * applied to the Order, so that a payment wins wherever it is found, by the jobs as by the webhook worker. No charge is
 * made until the payer pays, so a Checkout with none was not paid. Asaas is called outside any transaction.
 */
@Component
class CheckoutRereads {

    private final AsaasGateway asaas;
    private final OrderPayments payments;

    CheckoutRereads(AsaasGateway asaas, OrderPayments payments) {
        this.asaas = asaas;
        this.payments = payments;
    }

    /**
     * Re-reads the Checkout's charges, applying each to its Order until one pays it, and answers what they showed. Any
     * call Asaas could not take or refused is thrown, and leaves the Order as it was, unless a charge read before it
     * paid the Order.
     */
    Outcome reread(String checkoutId) {
        boolean held = false;
        for (String chargeId : asaas.chargesOfCheckout(checkoutId)) {
            AsaasGateway.Charge charge = asaas.charge(chargeId);
            if (payments.applyReread(charge)) {
                return Outcome.PAID;
            }
            held |= charge.awaitingRiskAnalysis();
        }
        return held ? Outcome.HELD_FOR_RISK_ANALYSIS : Outcome.UNPAID;
    }

    /**
     * What the Checkout's charges showed: one paid the Order; or one is held for Asaas's manual risk analysis, which
     * keeps the Order awaiting payment; or nothing paid it.
     */
    enum Outcome {
        PAID,
        HELD_FOR_RISK_ANALYSIS,
        UNPAID
    }
}
