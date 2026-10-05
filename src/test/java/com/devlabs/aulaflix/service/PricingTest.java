package com.devlabs.aulaflix.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.junit.jupiter.api.Test;

import com.devlabs.aulaflix.dto.CoursePricing;

/**
 * The prices the web shows ready-made: the Pix price, rounded down to the cent in the Student's favour, and the
 * installment at the most installments, which the catalog only takes when it is exact.
 */
class PricingTest {

    /** Every price the catalog takes, up to the largest a column of cents holds, with any Pix discount. */
    private static final Generator<PixDiscount> PIX_DISCOUNTS = Generator.from(data -> new PixDiscount(
            data.generate(Generator.integers(1, Integer.MAX_VALUE)), data.generate(Generator.integers(0, 99))));

    /** Every price that divides by the installments, the only ones the catalog takes. */
    private static final Generator<Installments> INSTALLMENTS = Generator.from(data -> {
        int maxInstallments = data.generate(Generator.integers(1, 12));
        int installmentCents = data.generate(Generator.integers(1, Integer.MAX_VALUE / maxInstallments));
        return new Installments(installmentCents * maxInstallments, maxInstallments);
    });

    @Test
    void roundsThePixPriceDownToTheCent() {
        PropertyChecker.forAll(PIX_DISCOUNTS, discount -> {
            long pixPriceCents = Pricing.of(discount.priceCents(), discount.percent(), 1).pixPriceCents();
            long discountedHundredths = (long) discount.priceCents() * (100 - discount.percent());
            return pixPriceCents * 100 <= discountedHundredths && discountedHundredths < (pixPriceCents + 1) * 100;
        });
    }

    @Test
    void neverPutsThePixPriceAboveThePrice() {
        PropertyChecker.forAll(PIX_DISCOUNTS, discount ->
                Pricing.of(discount.priceCents(), discount.percent(), 1).pixPriceCents() <= discount.priceCents());
    }

    @Test
    void chargesThePriceExactlyAcrossTheMostInstallments() {
        PropertyChecker.forAll(INSTALLMENTS, installments -> {
            CoursePricing pricing = Pricing.of(installments.priceCents(), 10, installments.maxInstallments());
            return (long) pricing.installmentCents() * installments.maxInstallments() == installments.priceCents();
        });
    }

    @Test
    void pricesACourseOfR$497At10PercentOffOnPixIn10Installments() {
        assertThat(Pricing.of(49700, 10, 10)).isEqualTo(new CoursePricing(49700, 10, 44730, 10, 4970));
    }

    @Test
    void cutsTheHalfCentOffThePixPrice() {
        assertThat(Pricing.of(49990, 15, 10)).isEqualTo(new CoursePricing(49990, 15, 42491, 10, 4999));
    }

    @Test
    void chargesThePriceOnPixWithoutADiscountAndInOnePayment() {
        assertThat(Pricing.of(1, 0, 1)).isEqualTo(new CoursePricing(1, 0, 1, 1, 1));
    }

    private record PixDiscount(int priceCents, int percent) {
    }

    private record Installments(int priceCents, int maxInstallments) {
    }
}
