package com.devlabs.aulaflix.service;

import java.time.Duration;

/**
 * How long reconciliation leaves a paid Order before it re-reads its charge again, so that money going back whose
 * webhook was lost still ends access, without a call per paid Order on every run.
 */
public record PaidRecheckInterval(Duration value) {
}
