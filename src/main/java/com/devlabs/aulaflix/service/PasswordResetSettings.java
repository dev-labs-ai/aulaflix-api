package com.devlabs.aulaflix.service;

import java.time.Duration;

/**
 * How the password reset answers: every request for a code takes at least {@code codeRequestTime}, whether or not the
 * email has a Student Account, so long as the work for a Student's email takes less.
 */
public record PasswordResetSettings(Duration codeRequestTime) {
}
