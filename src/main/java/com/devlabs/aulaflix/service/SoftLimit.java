package com.devlabs.aulaflix.service;

/**
 * The soft limits on an operation: past {@code perIp} requests from one client IP, or past {@code global} requests from
 * everyone at once, each request needs a solved CAPTCHA until the window ends. They count apart from the operation's
 * hard limits, so their limits are named apart from them.
 */
public record SoftLimit(String operation, RateLimit perIp, RateLimit global) {
}
