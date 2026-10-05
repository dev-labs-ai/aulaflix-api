package com.devlabs.aulaflix.config;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The outbox, read from {@code aulaflix.outbox.*}: the From of every email; the send rate, at most
 * {@code send-rate.emails} attempts within any {@code send-rate.per}, which production keeps below the SES account's
 * maximum; and how long the drainer rests between drains. SMTP itself is {@code spring.mail.*}.
 */
@Validated
@ConfigurationProperties("aulaflix.outbox")
public record OutboxProperties(
        @NotBlank
        String from,
        @NotNull
        @Valid
        SendRate sendRate,
        @NotNull
        @DurationMin(millis = 1)
        Duration drainInterval) {

    public record SendRate(
            @Positive
            int emails,
            @NotNull
            @DurationMin(millis = 1)
            Duration per) {
    }
}
