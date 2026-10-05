package com.devlabs.aulaflix.config;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code aulaflix.password-reset.code-request-time}: how long every request for a reset code takes at least, longer
 * than the work a Student's email asks for, so that the answer's timing never tells who has an Account.
 */
@Validated
@ConfigurationProperties("aulaflix.password-reset")
public record PasswordResetProperties(
        @NotNull
        Duration codeRequestTime) {
}
