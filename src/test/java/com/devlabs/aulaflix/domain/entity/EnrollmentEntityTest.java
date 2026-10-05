package com.devlabs.aulaflix.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.devlabs.aulaflix.domain.EnrollmentEndReason;

/**
 * An Enrollment an Order granted ends with its Order, for the money that left: a Refund, a chargeback, or an upheld
 * Pix block. A manual one ends only by hand, with an Admin and a note, and no Enrollment ends twice.
 */
class EnrollmentEntityTest {

    private static final Instant STARTED_AT = Instant.parse("2026-10-05T12:00:00Z");
    private static final Instant LATER = STARTED_AT.plus(Duration.ofDays(3));

    private final CourseEntity course = new CourseEntity("backend-com-node", "Backend com Node.js");
    private final AccountEntity student = new AccountEntity("bia@example.com", "Bia", "hash", Role.STUDENT,
            STARTED_AT);
    private final AccountEntity admin = new AccountEntity("ana@aulaflix.com.br", "Ana", "hash", Role.ADMIN,
            STARTED_AT);

    @ParameterizedTest
    @EnumSource(value = EnrollmentEndReason.class, names = {"REFUND", "CHARGEBACK", "PIX_BLOCK_UPHELD"})
    void anOrdersEnrollmentEndsWithItsOrderForTheMoneyThatLeft(EnrollmentEndReason reason) {
        EnrollmentEntity enrollment = grantedByAnOrder();

        enrollment.endWithItsOrder(LATER, reason);

        assertThat(enrollment.isActive()).isFalse();
        assertThat(enrollment.getEndedAt()).isEqualTo(LATER);
        assertThat(enrollment.getEndReason()).isEqualTo(reason);
        assertThat(enrollment.getEndedBy()).isNull();
    }

    @Test
    void anOrdersEnrollmentNeverEndsWithItsOrderAsManual() {
        EnrollmentEntity enrollment = grantedByAnOrder();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> enrollment.endWithItsOrder(LATER, EnrollmentEndReason.MANUAL));
        assertThat(enrollment.isActive()).isTrue();
    }

    @Test
    void aManualEnrollmentNeverEndsWithAnOrder() {
        EnrollmentEntity enrollment = EnrollmentEntity.grantedManually(student, course, STARTED_AT, admin,
                "Cortesia.");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> enrollment.endWithItsOrder(LATER, EnrollmentEndReason.REFUND));
        assertThat(enrollment.isActive()).isTrue();
    }

    @Test
    void anEndedEnrollmentNeverEndsAgain() {
        EnrollmentEntity enrollment = grantedByAnOrder();
        enrollment.endWithItsOrder(LATER, EnrollmentEndReason.REFUND);

        assertThatIllegalStateException()
                .isThrownBy(() -> enrollment.endWithItsOrder(LATER.plusSeconds(1), EnrollmentEndReason.CHARGEBACK));
        assertThat(enrollment.getEndReason()).isEqualTo(EnrollmentEndReason.REFUND);
        assertThat(enrollment.getEndedAt()).isEqualTo(LATER);
    }

    private EnrollmentEntity grantedByAnOrder() {
        course.setPriceCents(49700);
        course.setPixDiscountPercent(10);
        OrderEntity order = OrderEntity.pix("K7M2Q9XA", student, course, 44730, STARTED_AT,
                STARTED_AT.plus(Duration.ofMinutes(30)));
        order.pay(STARTED_AT);
        return EnrollmentEntity.grantedByOrder(order, STARTED_AT);
    }
}
