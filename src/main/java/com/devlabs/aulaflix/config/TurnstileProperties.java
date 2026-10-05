package com.devlabs.aulaflix.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Cloudflare Turnstile, read from {@code aulaflix.turnstile.*}: where {@code siteverify} is, Cloudflare's by default;
 * the widget's secret key, the secret file {@code aulaflix.turnstile.secret-key}, without which the API refuses to
 * start; how long a request past a soft limit waits for {@code siteverify}; and the {@code Retry-After} it gets when
 * {@code siteverify} cannot answer.
 */
@Validated
@ConfigurationProperties("aulaflix.turnstile")
public record TurnstileProperties(
        /** Up to and including {@code /v0}. */
        @NotNull
        URI baseUrl,

        @NotBlank
        String secretKey,

        /** Both the connect and the read timeout of each verification. */
        @NotNull
        @DurationMin(millis = 1)
        Duration timeout,

        @NotNull
        @DurationMin(seconds = 1)
        Duration retryAfter) {

    /** Never shows the secret key, wherever the properties end up printed. */
    @Override
    public String toString() {
        return "TurnstileProperties[baseUrl=" + baseUrl + ", timeout=" + timeout + ", retryAfter=" + retryAfter + "]";
    }
}
