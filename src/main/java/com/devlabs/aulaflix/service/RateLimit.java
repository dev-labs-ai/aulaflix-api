package com.devlabs.aulaflix.service;

import java.time.Duration;

/** A limit: at most {@code requests} of the operation per key within each {@code window}. */
public record RateLimit(String operation, int requests, Duration window) {
}
