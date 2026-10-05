package com.devlabs.aulaflix.service;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.entity.OrderEntity;
import com.devlabs.aulaflix.repository.AccountRepository;
import com.devlabs.aulaflix.repository.OrderRepository;

/**
 * The transactions of the expiry and reconciliation jobs, each short, since Asaas is never called inside one. Changing
 * an Order holds its Student's row lock, like every placement and every payment, so that whatever moves one Student's
 * Orders goes one at a time, and each finds the Order as the one before left it.
 */
@Component
class OrderUpkeep {

    private static final Logger log = LoggerFactory.getLogger(OrderUpkeep.class);

    private final OrderRepository orders;
    private final AccountRepository accounts;

    OrderUpkeep(OrderRepository orders, AccountRepository accounts) {
        this.orders = orders;
        this.accounts = accounts;
    }

    /** The Orders still awaiting payment that expired by the moment given, the earliest due first. */
    @Transactional(readOnly = true)
    List<DueOrder> expiredBy(Instant now) {
        return orders.findAwaitingExpiredBy(now).stream().map(OrderUpkeep::due).toList();
    }

    /** The Orders with a charge that have awaited payment since the moment given or before, the oldest first. */
    @Transactional(readOnly = true)
    List<DueOrder> awaitingSince(Instant before) {
        return orders.findAwaitingWithChargePlacedBy(before).stream().map(OrderUpkeep::due).toList();
    }

    /** The cancelled Orders placed by the moment given that may have charges left at Asaas, the oldest first. */
    @Transactional(readOnly = true)
    List<DueOrder> withChargesToDeleteSince(Instant before) {
        return orders.findWithChargesToDeletePlacedBy(before).stream().map(OrderUpkeep::due).toList();
    }

    /** Expires the Order, unless something, a payment above all, moved it on meanwhile. */
    @Transactional
    void expire(DueOrder due) {
        accounts.findLockedById(due.studentId()).orElseThrow();
        if (orders.findById(due.id()).orElseThrow().expire()) {
            log.info("Order {} expired", due.code());
        }
    }

    private static DueOrder due(OrderEntity order) {
        return new DueOrder(order.getId(), order.getCode(), order.getStudent().getId(), order.getAsaasPaymentId());
    }

    /** An Order a job has to look at: its Student's id, and its charge's, which is null until Asaas gave one. */
    record DueOrder(long id, String code, long studentId, String chargeId) {
    }
}
