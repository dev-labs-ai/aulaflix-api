package com.devlabs.aulaflix.service;

import java.time.Duration;

import jakarta.mail.internet.InternetAddress;

/**
 * How the outbox sends: from whom, and at most how many emails within any span of time, an attempt that fails
 * included, so that it stays below what the SMTP server accepts.
 */
public record OutboxSettings(InternetAddress from, int emailsPerSpan, Duration span) {
}
