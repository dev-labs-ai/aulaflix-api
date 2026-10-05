package com.devlabs.aulaflix.service;

import java.time.Duration;

/**
 * How long an Order is left to its webhook before reconciliation re-reads it, and how long a failed placement's charge
 * is given to reach Asaas before reconciliation searches for it.
 */
public record ReconciliationDelay(Duration value) {
}
