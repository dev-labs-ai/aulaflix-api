package com.devlabs.aulaflix.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Asaas, read from {@code aulaflix.asaas.*}: its API's base URL, the sandbox's by default; the API key and the webhook's
 * token, secret files named after their properties, without which the API refuses to start; how long a call waits for
 * Asaas; the {@code Retry-After} a Student gets while Asaas cannot be reached; and how often the webhook worker runs.
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
        Duration retryAfter,

        /** What Asaas sends in {@code asaas-access-token}: 32 to 255 characters, as Asaas takes them. */
        @NotBlank
        @Size(min = 32, max = 255)
        String webhookToken,

        /** How long the webhook worker rests between runs. */
        @NotNull
        @DurationMin(millis = 1)
        Duration webhookInterval) {

    /** Never shows the key nor the token, wherever the properties end up printed. */
    @Override
    public String toString() {
        return "AsaasProperties[baseUrl=" + baseUrl + ", timeout=" + timeout + ", retryAfter=" + retryAfter
                + ", webhookInterval=" + webhookInterval + "]";
    }
}
