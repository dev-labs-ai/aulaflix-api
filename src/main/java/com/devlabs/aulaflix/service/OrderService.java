package com.devlabs.aulaflix.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devlabs.aulaflix.domain.OrderStatus;
import com.devlabs.aulaflix.domain.PaymentMethod;
import com.devlabs.aulaflix.dto.Order;
import com.devlabs.aulaflix.dto.OrderList;
import com.devlabs.aulaflix.dto.OrderRequest;
import com.devlabs.aulaflix.dto.PlacedOrder;
import com.devlabs.aulaflix.exception.AsaasRefusedException;
import com.devlabs.aulaflix.exception.AsaasUnavailableException;
import com.devlabs.aulaflix.exception.CourseNotForSaleException;
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
    private final OrderCancellations cancellations;
    private final OrderExpiry expiry;
    private final OrderRepository repository;
    private final AsaasGateway asaas;
    private final RateLimiter limiter;
    private final CheckoutLimits limits;
    private final CheckoutReturns returns;
    private final Clock clock;

    public OrderService(OrderPlacements placements, OrderCancellations cancellations, OrderExpiry expiry,
                        OrderRepository repository, AsaasGateway asaas, RateLimiter limiter, CheckoutLimits limits,
                        CheckoutReturns returns, Clock clock) {
        this.placements = placements;
        this.cancellations = cancellations;
        this.expiry = expiry;
        this.repository = repository;
        this.asaas = asaas;
        this.limiter = limiter;
        this.limits = limits;
        this.returns = returns;
        this.clock = clock;
    }

    /**
     * Places an Order for an On sale Course, or answers the one already awaiting payment by the same method, which
     * makes nothing new at Asaas. One awaiting payment by the other method is cancelled first, at Asaas too; when Asaas
     * fails it stays awaiting, and nothing is placed. A Pix is a charge; a Student's first Pix makes their Asaas
     * customer with the CPF, which is never stored. A card is paid on an Asaas Checkout. Every placement counts against
     * the Student's limit and everyone's, whatever it answers. A Course id of any shape answers like an unknown one.
     */
    public PlacedOrder place(long studentId, OrderRequest request) {
        limiter.consume(limits.perStudent(), RateLimitKey.student(studentId));
        limiter.consume(limits.everyone(), RateLimitKey.everyone());
        long courseId = PathIds.parse(request.courseId()).orElseThrow(CourseNotForSaleException::new);
        OrderPlacements.Placement placement = openExpiringALapsedOrder(studentId, courseId, request);
        if (placement instanceof OrderPlacements.Placement.Existing existing
                && existing.order().method() != request.method()) {
            cancellations.replace(studentId, existing.order().code());
            placement = placements.open(studentId, courseId, request.method(), request.cpf());
        }
        return switch (placement) {
            case OrderPlacements.Placement.Existing existing -> new PlacedOrder(existing.order(), false);
            case OrderPlacements.Placement.NewCard card -> new PlacedOrder(checkout(card), true);
            case OrderPlacements.Placement.NewPix pix -> new PlacedOrder(charge(studentId, pix), true);
        };
    }

    /**
     * Opens the placement; an Order found awaiting payment past its {@code expiresAt} expires first, as the job would
     * have, and the placement opens again. That Order is answered after all only when it stays awaiting: Asaas holds
     * its card for risk analysis. When Asaas cannot be reached, the Order stays as it was, and nothing is placed.
     */
    private OrderPlacements.Placement openExpiringALapsedOrder(long studentId, long courseId, OrderRequest request) {
        OrderPlacements.Placement placement = placements.open(studentId, courseId, request.method(), request.cpf());
        if (!(placement instanceof OrderPlacements.Placement.Lapsed lapsed)) {
            return placement;
        }
        try {
            expiry.expire(lapsed.due());
        } catch (AsaasUnavailableException failure) {
            throw new PaymentUnavailableException("Order %s left awaiting payment".formatted(lapsed.order().code()),
                    failure);
        }
        return placements.open(studentId, courseId, request.method(), request.cpf());
    }

    /**
     * Cancels the Student's Order awaiting payment, at Asaas first, or answers it again once cancelled. When Asaas
     * fails, the Order stays awaiting payment.
     */
    public Order cancel(long studentId, String code) {
        return cancellations.cancel(studentId, code);
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

    /**
     * Makes the card Order's Checkout. When Asaas fails, the Order is cancelled, and nothing is left to undo: a
     * Checkout made all the same, under a call that timed out, has a link no one got, and expires on its own.
     */
    private Order checkout(OrderPlacements.Placement.NewCard placement) {
        AsaasGateway.Checkout checkout = atAsaas(placement.code(), () -> asaas.createCardCheckout(
                new AsaasGateway.CardCheckout(placement.code(), placement.courseTitle(),
                        description(placement.code(), placement.courseTitle()), placement.amountCents(),
                        placement.maxInstallments(), OrderPlacements.CARD_LIFETIME,
                        returns.after(placement.courseSlug(), placement.code()),
                        returns.afterCancelling(placement.courseSlug(), placement.code()))),
                () -> placements.cancel(placement.orderId(), false));
        return placements.recordCheckout(placement.orderId(), checkout);
    }

    /**
     * Makes the Pix Order's charge, and the Student's Asaas customer first when it is their first Pix. When Asaas fails
     * a step, the Order is cancelled, with whatever that step may have left at Asaas.
     */
    private Order charge(long studentId, OrderPlacements.Placement.NewPix placement) {
        String customerId = customerOf(studentId, placement);
        String chargeId = atAsaas(placement.code(), () -> asaas.createPixCharge(customerId, placement.amountCents(),
                        LocalDate.now(clock.withZone(ASAAS_ZONE)), placement.code(),
                        description(placement.code(), placement.courseTitle())),
                () -> cancelLeavingACharge(placement,
                        () -> asaas.chargesUnder(placement.code()).forEach(asaas::deleteCharge)));
        AsaasGateway.PixQrCode qrCode = atAsaas(placement.code(), () -> asaas.pixQrCode(chargeId),
                () -> cancelLeavingACharge(placement, () -> {
                    asaas.deleteCharge(chargeId);
                    placements.chargesDeleted(placement.orderId());
                }));
        return placements.recordPixCharge(placement.orderId(), chargeId, qrCode);
    }

    /**
     * The Student's Asaas customer: the one an earlier Pix made, or a new one with the CPF, which Asaas may refuse as
     * the Student's mistake to fix.
     */
    private String customerOf(long studentId, OrderPlacements.Placement.NewPix placement) {
        return switch (placement.payer()) {
            case OrderPlacements.Placement.Customer customer -> customer.id();
            case OrderPlacements.Placement.NewCustomer customer -> atAsaas(placement.code(),
                    () -> placements.recordCustomer(studentId, asaas.createCustomer(customer.name(), customer.cpf())),
                    () -> placements.cancel(placement.orderId(), false),
                    refusal -> refusal.refusedTheCpf()
                            ? new InvalidRequestException(List.of(new FieldViolation("cpf", "invalid-cpf")))
                            : new PaymentProviderErrorException(cancelled(placement.code()), refusal));
        };
    }

    /**
     * One step of a placement at Asaas. When Asaas fails it, what the placement left is undone, the Order cancelled
     * first, and the failure answered as a payment problem.
     */
    private static <T> T atAsaas(String code, Supplier<T> call, Runnable undo) {
        return atAsaas(code, call, undo, refusal -> new PaymentProviderErrorException(cancelled(code), refusal));
    }

    private static <T> T atAsaas(String code, Supplier<T> call, Runnable undo,
                                 Function<AsaasRefusedException, RuntimeException> refused) {
        try {
            return call.get();
        } catch (AsaasUnavailableException failure) {
            undo.run();
            throw new PaymentUnavailableException(cancelled(code), failure);
        } catch (AsaasRefusedException refusal) {
            undo.run();
            throw refused.apply(refusal);
        }
    }

    /**
     * Cancels the Order once a charge was asked for, keeping it to delete, and deletes the charge Asaas made for it:
     * by its id, or, when its id never came back, any charge made under the Order's code, since a timeout may hide one
     * that was made. Only a deletion by the charge's id settles it; reconciliation searches the code again once a
     * charge whose creation timed out would have reached Asaas, and deletes what a failed deletion left.
     */
    private void cancelLeavingACharge(OrderPlacements.Placement.NewPix placement, Runnable deletion) {
        placements.cancel(placement.orderId(), true);
        try {
            deletion.run();
        } catch (AsaasUnavailableException | AsaasRefusedException failure) {
            log.warn("Left the charges of cancelled Order {} to reconciliation: {}", placement.code(),
                    failure.getMessage());
        }
    }

    private static String cancelled(String code) {
        return "Order %s cancelled".formatted(code);
    }

    private static String description(String code, String courseTitle) {
        return "Pedido %s: %s".formatted(code, courseTitle);
    }
}
