package com.devlabs.aulaflix.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.devlabs.aulaflix.service.RateLimit;

/**
 * The hard limits, read from {@code aulaflix.rate-limits.*}; their defaults, in {@code application.properties}, are the
 * spec's numbers. A limit that is missing or counts nothing stops the API from starting.
 */
@Validated
@ConfigurationProperties("aulaflix.rate-limits")
public record RateLimitProperties(
        @NotNull
        @Valid
        Limit bffRequests,
        @NotNull
        @Valid
        Limit visitorPlayback,
        @NotNull
        @Valid
        Limit lookUpsAndSignIns,
        @NotNull
        @Valid
        Limit signUps,
        @NotNull
        @Valid
        Limit checkoutsPerStudent,
        @NotNull
        @Valid
        Limit checkoutsPerIp,
        @NotNull
        @Valid
        Limit checkouts,
        @NotNull
        @Valid
        Limit emailConfirmations,
        @NotNull
        @Valid
        Limit passwordResetCodes,
        @NotNull
        @Valid
        Limit passwordResets,
        @NotNull
        @Valid
        Limit waitlistEntries) {

    /** Every BFF request, whatever it asks for, per client IP. */
    RateLimit bffRequestLimit() {
        return bffRequests.named("bff-requests");
    }

    /** Every playback without a session, whatever it answers, per client IP. */
    RateLimit visitorPlaybackLimit() {
        return visitorPlayback.named("visitor-playback");
    }

    /** Every email look-up and sign-in, counted together, whatever they answer, per client IP. */
    RateLimit lookUpAndSignInLimit() {
        return lookUpsAndSignIns.named("look-ups-and-sign-ins");
    }

    /** Every sign-up, whatever it answers, per client IP. */
    RateLimit signUpLimit() {
        return signUps.named("sign-ups");
    }

    /** Every Order placement, whatever it answers, per Student. */
    RateLimit checkoutPerStudentLimit() {
        return checkoutsPerStudent.named("checkouts-per-student");
    }

    /** Every Order placement, whatever it answers, per client IP. */
    RateLimit checkoutPerIpLimit() {
        return checkoutsPerIp.named("checkouts-per-ip");
    }

    /** Every Order placement, whatever it answers, of everyone at once. */
    RateLimit checkoutLimit() {
        return checkouts.named("checkouts");
    }

    /** Every post of a confirmation link, whatever it answers, per client IP. */
    RateLimit emailConfirmationLimit() {
        return emailConfirmations.named("email-confirmations");
    }

    /** Every request for a reset code, whatever it answers, per client IP. */
    RateLimit passwordResetCodeLimit() {
        return passwordResetCodes.named("password-reset-codes");
    }

    /** Every reset with a code, whatever it answers, per client IP. */
    RateLimit passwordResetLimit() {
        return passwordResets.named("password-resets");
    }

    /** Every join of a Waitlist by email, whatever it answers, per client IP. */
    RateLimit waitlistEntryLimit() {
        return waitlistEntries.named("waitlist-entries");
    }

    /** At most {@code requests} per {@code window}, a window being at least a second. */
    public record Limit(
            @Positive
            int requests,
            @NotNull
            @DurationMin(seconds = 1)
            Duration window) {

        RateLimit named(String operation) {
            return new RateLimit(operation, requests, window);
        }
    }
}
