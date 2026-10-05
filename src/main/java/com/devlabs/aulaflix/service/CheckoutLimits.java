package com.devlabs.aulaflix.service;

/**
 * The hard limits on placing Orders that the Orders module counts itself: per Student, and for everyone at once, which
 * keeps scripts from draining the Asaas quota. The limit per client IP is counted before the request gets here.
 */
public record CheckoutLimits(RateLimit perStudent, RateLimit everyone) {
}
