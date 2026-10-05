package com.devlabs.aulaflix.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The secret the 6-digit codes are stored under, as HMACs, read from the secret file {@code aulaflix.codes.hmac-key}.
 * Without one worth the name, the API refuses to start: a plain hash of a million codes reverses offline.
 */
@Validated
@ConfigurationProperties("aulaflix.codes")
public record CodeProperties(
        @NotBlank
        @Size(min = 32)
        String hmacKey) {

    /** Keeps the key out of anything that prints the properties. */
    @Override
    public String toString() {
        return "CodeProperties[hmacKey=<hidden>]";
    }
}
