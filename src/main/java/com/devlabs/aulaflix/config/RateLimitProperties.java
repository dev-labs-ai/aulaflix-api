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
        Limit bffRequests) {

    /** Every BFF request, whatever it asks for, per client IP. */
    RateLimit bffRequestLimit() {
        return bffRequests.named("bff-requests");
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
