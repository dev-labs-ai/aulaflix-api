package com.devlabs.aulaflix.service;

import com.devlabs.aulaflix.dto.CoursePricing;

/** The pricing maths, done once here, so the web never does it. */
final class Pricing {

    private Pricing() {
    }

    /**
     * The Pix price is rounded down to the cent, in the Student's favour; the product is taken as a {@code long}, since
     * a price in cents times 100 outgrows an {@code int}. The installment is exact: the catalog takes no price that
     * leaves a remainder.
     */
    static CoursePricing of(int priceCents, int pixDiscountPercent, int maxInstallments) {
        int pixPriceCents = (int) ((long) priceCents * (100 - pixDiscountPercent) / 100);
        return new CoursePricing(priceCents, pixDiscountPercent, pixPriceCents, maxInstallments,
                priceCents / maxInstallments);
    }
}
