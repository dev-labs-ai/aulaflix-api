package com.devlabs.aulaflix.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where HIBP's range API is, {@code aulaflix.hibp.base-url}, and how long a new password waits for its answer before
 * it is taken unchecked, {@code aulaflix.hibp.timeout}.
 */
@Validated
@ConfigurationProperties("aulaflix.hibp")
public record HibpProperties(
        @NotNull
        URI baseUrl,
        @NotNull
        @DurationMin(millis = 1)
        Duration timeout) {
}
