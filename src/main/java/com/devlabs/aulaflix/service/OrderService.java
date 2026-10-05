package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.dto.Order;
import com.devlabs.aulaflix.dto.OrderList;
import com.devlabs.aulaflix.dto.OrderRequest;
import com.devlabs.aulaflix.dto.PlacedOrder;
import com.devlabs.aulaflix.exception.FieldViolation;
import com.devlabs.aulaflix.exception.InvalidRequestException;
import com.devlabs.aulaflix.exception.OrderNotFoundException;
import com.devlabs.aulaflix.exception.PaymentProviderErrorException;
import com.devlabs.aulaflix.exception.PaymentUnavailableException;
import com.devlabs.aulaflix.repository.OrderRepository;

/**
 * The Orders module, as the Student meets it: placing an Order and reading their own. An Order is written before Asaas
 * is called, and Asaas is never called inside a transaction. When Asaas fails the Order is cancelled, and any charge
 * made under its code is deleted at once; one the deletion misses is left for reconciliation.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** Asaas's dates are Brazil's. */
    private static final ZoneId ASAAS_ZONE = ZoneId.of("America/Sao_Paulo");

    /** Orders that were never paid stay out of the Student's list. */
    private static final Set<OrderStatus> LISTED = EnumSet.of(OrderStatus.AWAITING_PAYMENT, OrderStatus.PAID,
            OrderStatus.REFUNDING, OrderStatus.REFUNDED, OrderStatus.REVERSED);

    private final OrderPlacements placements;
    private final OrderRepository repository;
    private final AsaasGateway asaas;
    private final RateLimiter limiter;
    private final CheckoutLimits limits;
    private final Clock clock;

    public OrderService(OrderPlacements placements, OrderRepository repository, AsaasGateway asaas,
                        RateLimiter limiter, CheckoutLimits limits, Clock clock) {
        this.placements = placements;
        this.repository = repository;
        this.asaas = asaas;
        this.limiter = limiter;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * Places a Pix Order for an On sale Course, or answers the one already awaiting payment, which makes no new
     * charge. A Student's first Pix makes their Asaas customer with the CPF, which is never stored. Every placement
     * counts against the Student's limit and everyone's, whatever it answers.
     */
    public PlacedOrder place(long studentId, OrderRequest request) {
        limiter.consume(limits.perStudent(), RateLimitKey.student(studentId));
        limiter.consume(limits.everyone(), RateLimitKey.everyone());
        OrderPlacements.Placement placement = placements.open(studentId, request.courseId(), request.cpf());
        if (placement.existing() != null) {
            return new PlacedOrder(placement.existing(), false);
        }
        return new PlacedOrder(charge(studentId, placement), true);
    }

    /** The Student's Orders, newest first, but those never paid. */
    @Transactional(readOnly = true)
    public OrderList list(long studentId) {
        return new OrderList(repository.findAllOfStudentInStatuses(studentId, LISTED).stream()
                .map(OrderViews::summary)
                .toList());
    }

    /** One of the Student's Orders, in any state; another Student's code answers like an unknown one. */
    @Transactional(readOnly = true)
    public Order get(long studentId, String code) {
        return repository.findOfStudentByCode(studentId, code).map(OrderViews::withPayment)
                .orElseThrow(OrderNotFoundException::new);
    }

    private Order charge(long studentId, OrderPlacements.Placement placement) {
        Charging charging = new Charging();
        try {
            String customerId = placement.customerId() != null ? placement.customerId()
                    : placements.recordCustomer(studentId,
                            asaas.createCustomer(placement.studentName(), placement.cpf()));
            charging.started = true;
            charging.chargeId = asaas.createPixCharge(customerId, placement.amountCents(),
                    LocalDate.now(clock.withZone(ASAAS_ZONE)), placement.code(),
                    "Pedido %s: %s".formatted(placement.code(), placement.courseTitle()));
            return placements.recordPixCharge(placement.orderId(), charging.chargeId,
                    asaas.pixQrCode(charging.chargeId));
        } catch (AsaasUnavailableException failure) {
            cancel(placement, charging);
            throw new PaymentUnavailableException("Order %s cancelled; %s".formatted(placement.code(),
                    failure.getMessage()), failure.retryAfter());
        } catch (AsaasRefusedException refusal) {
            cancel(placement, charging);
            if (!charging.started && refusal.refusedTheCpf()) {
                throw new InvalidRequestException(List.of(new FieldViolation("cpf", "invalid-cpf")));
            }
            throw new PaymentProviderErrorException("Order %s cancelled; %s".formatted(placement.code(),
                    refusal.getMessage()));
        }
    }

    /**
     * Cancels the Order, and deletes the charge Asaas made for it, or, when its id never came back, any charge made
     * under the Order's code: a timeout may hide one that was made. Only a deletion by the charge's id settles it;
     * reconciliation searches the code again once a charge whose creation timed out would have reached Asaas, and
     * deletes what a failed deletion left.
     */
    private void cancel(OrderPlacements.Placement placement, Charging charging) {
        placements.cancel(placement.orderId(), charging.started);
        if (!charging.started) {
            return;
        }
        try {
            if (charging.chargeId != null) {
                asaas.deleteCharge(charging.chargeId);
                placements.chargesDeleted(placement.orderId());
            } else {
                asaas.chargesUnder(placement.code()).forEach(asaas::deleteCharge);
            }
        } catch (AsaasUnavailableException | AsaasRefusedException failure) {
            log.warn("Left the charges of cancelled Order {} to reconciliation: {}", placement.code(),
                    failure.getMessage());
        }
    }

    /** How far a placement got at Asaas: whether a charge was asked for, and its id once Asaas gave one. */
    private static final class Charging {

        private boolean started;
        private String chargeId;
    }
}
