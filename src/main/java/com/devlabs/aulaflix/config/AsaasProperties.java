package com.devlabs.aulaflix.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Asaas, read from {@code aulaflix.asaas.*}: its API's base URL, the sandbox's by default; the API key, a secret file
 * named after its property, without which the API refuses to start; how long a call waits for Asaas; and the
 * {@code Retry-After} a Student gets while Asaas cannot be reached.
 */
@Validated
@ConfigurationProperties("aulaflix.asaas")
public record AsaasProperties(
        /** Up to and including {@code /v3}. */
        @NotNull
        URI baseUrl,

        @NotBlank
        String apiKey,

        /** Both the connect and the read timeout of each call. */
        @NotNull
        @DurationMin(millis = 1)
        Duration timeout,

        @NotNull
        @DurationMin(seconds = 1)
        Duration retryAfter) {

    /** Never shows the key, wherever the properties end up printed. */
    @Override
    public String toString() {
        return "AsaasProperties[baseUrl=" + baseUrl + ", timeout=" + timeout + ", retryAfter=" + retryAfter + "]";
    }
}
