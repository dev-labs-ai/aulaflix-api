package com.devlabs.aulaflix.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.devlabs.aulaflix.domain.OrderStatus;

/**
 * A Refund or a Reversal moves an Order only forward: only a paid Order starts refunding or is reversed, and only a
 * refunding one becomes refunded. Whatever moved the Order on meanwhile, a late refund or a late report of one leaves
 * it, and its audit, as it is.
 */
class OrderEntityTest {

    private static final Instant PLACED_AT = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant LATER = PLACED_AT.plus(Duration.ofDays(2));

    private final AccountEntity admin = new AccountEntity("ana@aulaflix.com.br", "Ana", "hash", Role.ADMIN,
            PLACED_AT);

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"AWAITING_PAYMENT", "EXPIRED", "CANCELLED", "REFUNDING",
            "REFUNDED"})
    void startsRefundingOnlyAPaidOrder(OrderStatus status) {
        OrderEntity order = orderIn(status);
        Instant requestedBefore = order.getRefundRequestedAt();

        assertThat(order.refunding(LATER, admin)).isFalse();

        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getRefundRequestedAt()).isEqualTo(requestedBefore);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"AWAITING_PAYMENT", "EXPIRED", "CANCELLED", "PAID", "REFUNDED"})
    void becomesRefundedOnlyWhileRefunding(OrderStatus status) {
        OrderEntity order = orderIn(status);
        Instant refundedBefore = order.getRefundedAt();

        assertThat(order.refunded(LATER)).isFalse();

        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getRefundedAt()).isEqualTo(refundedBefore);
    }

    @Test
    void aPaidOrderRefundsAtTheAdminsRequestThenOnceDone() {
        OrderEntity order = orderIn(OrderStatus.PAID);

        assertThat(order.refunding(LATER, admin)).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDING);
        assertThat(order.getRefundRequestedAt()).isEqualTo(LATER);
        assertThat(order.getRefundRequestedBy()).isSameAs(admin);

        assertThat(order.refunded(LATER.plusSeconds(60))).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(order.getRefundedAt()).isEqualTo(LATER.plusSeconds(60));
    }

    @Test
    void matchesAChargeUnderItsCodeForItsAmount() {
        OrderEntity order = orderIn(OrderStatus.AWAITING_PAYMENT);

        assertThat(order.matchesCharge("K7M2Q9XA", null, new BigDecimal("447.30"))).isTrue();
        assertThat(order.matchesCharge("K7M2Q9XA", null, new BigDecimal("447.3"))).isTrue();
        assertThat(order.matchesCharge("K7M2Q9XA", null, new BigDecimal("447.29"))).isFalse();
        assertThat(order.matchesCharge("K7M2Q9XB", null, new BigDecimal("447.30"))).isFalse();
    }

    /** A Checkout's charges may come without its reference: the Checkout they were paid on names the Order then. */
    @Test
    void matchesAChargeWithoutAReferenceOnlyOnItsCheckout() {
        OrderEntity order = orderIn(OrderStatus.AWAITING_PAYMENT);
        BigDecimal amount = new BigDecimal("447.30");

        assertThat(order.matchesCharge(null, "chk_1", amount)).isFalse();
        order.recordCheckout("chk_1", "https://asaas.com/checkoutSession/show?id=chk_1");
        assertThat(order.matchesCharge(null, "chk_1", amount)).isTrue();
        assertThat(order.matchesCharge(null, "chk_2", amount)).isFalse();
        assertThat(order.matchesCharge(null, null, amount)).isFalse();
        assertThat(order.matchesCharge("K7M2Q9XB", "chk_1", amount)).isFalse();
    }

    @Test
    void aPaidOrderIsReversedOnce() {
        OrderEntity order = orderIn(OrderStatus.PAID);

        assertThat(order.reverse()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REVERSED);
        assertThat(order.reverse()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REVERSED);
    }

    /** A Reversal undoes a payment: an Order never paid has none, and a refunding one's money is going back already. */
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"AWAITING_PAYMENT", "EXPIRED", "CANCELLED", "REFUNDING",
            "REFUNDED"})
    void reversesOnlyAPaidOrder(OrderStatus status) {
        OrderEntity order = orderIn(status);

        assertThat(order.reverse()).isFalse();

        assertThat(order.getStatus()).isEqualTo(status);
    }

    /** An Order brought to the state the way the API brings one there. */
    private OrderEntity orderIn(OrderStatus status) {
        CourseEntity course = new CourseEntity("backend-com-node", "Backend com Node.js");
        course.setPriceCents(49700);
        course.setPixDiscountPercent(10);
        AccountEntity student = new AccountEntity("bia@example.com", "Bia", "hash", Role.STUDENT, PLACED_AT);
        OrderEntity order = OrderEntity.pix("K7M2Q9XA", student, course, 10, 44730, PLACED_AT,
                PLACED_AT.plus(Duration.ofMinutes(30)));
        Consumer<OrderEntity> paid = paying -> paying.pay(PLACED_AT.plusSeconds(60));
        switch (status) {
            case AWAITING_PAYMENT -> { }
            case EXPIRED -> order.expire();
            case CANCELLED -> order.cancel();
            case PAID -> paid.accept(order);
            case REFUNDING -> {
                paid.accept(order);
                order.refunding(PLACED_AT.plusSeconds(120), admin);
            }
            case REFUNDED -> {
                paid.accept(order);
                order.refunding(PLACED_AT.plusSeconds(120), admin);
                order.refunded(PLACED_AT.plusSeconds(180));
            }
            default -> throw new IllegalArgumentException("No way to " + status);
        }
        assertThat(order.getStatus()).isEqualTo(status);
        return order;
    }
}
