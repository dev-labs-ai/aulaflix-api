package com.devlabs.aulaflix.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The secret the BFF sends as {@code AulaFlix-BFF-Key}, shared with the web and read from the secret file
 * {@code aulaflix.bff.key}. Without it, the API refuses to start rather than let anyone around the BFF.
 */
@Validated
@ConfigurationProperties("aulaflix.bff")
public record BffProperties(
        @NotBlank
        @Size(min = 32)
        String key) {
}
