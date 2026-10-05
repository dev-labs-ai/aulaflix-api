package com.devlabs.aulaflix.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.devlabs.aulaflix.config.RateLimitProperties.Limit;
import com.devlabs.aulaflix.service.SoftLimit;

/**
 * The soft limits, past which a request needs a CAPTCHA, read from {@code aulaflix.soft-limits.<operation>.per-ip.*}
 * and {@code .global.*}; their defaults, in {@code application.properties}, are the spec's numbers. A limit that is
 * missing or counts nothing stops the API from starting.
 */
@Validated
@ConfigurationProperties("aulaflix.soft-limits")
public record SoftLimitProperties(
        @NotNull
        @Valid
        Thresholds lookUpsAndSignIns,
        @NotNull
        @Valid
        Thresholds signUps,
        @NotNull
        @Valid
        Thresholds passwordResetCodes,
        @NotNull
        @Valid
        Thresholds waitlistEntries) {

    /** Every email look-up and sign-in, counted together, whatever they answer. */
    SoftLimit lookUpAndSignInLimit() {
        return lookUpsAndSignIns.named("look-ups-and-sign-ins");
    }

    /** Every sign-up, whatever it answers. */
    SoftLimit signUpLimit() {
        return signUps.named("sign-ups");
    }

    /** Every request for a reset code, whatever it answers. */
    SoftLimit passwordResetCodeLimit() {
        return passwordResetCodes.named("password-reset-codes");
    }

    /** Every join of a Waitlist by email, whatever it answers. */
    SoftLimit waitlistEntryLimit() {
        return waitlistEntries.named("waitlist-entries");
    }

    /** The soft limits of one operation: per client IP, and for everyone at once. */
    public record Thresholds(
            @NotNull
            @Valid
            Limit perIp,
            @NotNull
            @Valid
            Limit global) {

        /** Named apart from the operation's hard limits, which count the same requests on counters of their own. */
        SoftLimit named(String operation) {
            String counted = "soft " + operation;
            return new SoftLimit(operation, perIp.named(counted), global.named(counted));
        }
    }
}
