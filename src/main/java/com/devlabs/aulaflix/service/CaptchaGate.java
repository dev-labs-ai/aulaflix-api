package com.devlabs.aulaflix.service;

import java.net.InetAddress;
import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.devlabs.aulaflix.exception.CaptchaRequiredException;
import com.devlabs.aulaflix.service.RateLimiter.Overrun;

/**
 * The soft limits: a CAPTCHA only when traffic looks abusive, so that normal use stays one step. Each request of an
 * operation counts against its client IP's soft limit and against everyone's; within both it passes whatever it
 * carries, and past either it passes only with a token that Turnstile accepts. A key that crosses a soft limit is
 * logged once per window, and a global limit tripping gets a WARN of its own.
 */
public class CaptchaGate {

    private static final Logger log = LoggerFactory.getLogger(CaptchaGate.class);

    /** Turnstile's tokens are at most this long; a longer one is not worth a call. */
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final RateLimiter limiter;
    private final TurnstileVerifier turnstile;
    private final Clock clock;

    public CaptchaGate(RateLimiter limiter, TurnstileVerifier turnstile, Clock clock) {
        this.limiter = limiter;
        this.turnstile = turnstile;
        this.clock = clock;
    }

    /**
     * Counts the request against the operation's soft limits, and lets it through when it is within both, or when
     * Turnstile accepts its token; it throws {@code CaptchaRequiredException} otherwise, and
     * {@code CaptchaUnavailableException} when Turnstile cannot say.
     */
    public void pass(SoftLimit limit, InetAddress clientIp, Optional<String> token) {
        RateLimitKey ip = RateLimitKey.clientIp(clientIp);
        Optional<Overrun> perIp = limiter.count(limit.perIp(), ip);
        Optional<Overrun> global = limiter.count(limit.global(), RateLimitKey.everyone());
        perIp.filter(Overrun::firstOfItsWindow).ifPresent(overrun -> log.warn(
                "Soft limit on {} crossed by {}: a CAPTCHA is required until {}", limit.operation(), ip,
                clock.instant().plus(overrun.untilWindowEnds())));
        global.filter(Overrun::firstOfItsWindow).ifPresent(overrun -> log.warn(
                "Global soft limit on {} tripped: a CAPTCHA is required from every client IP until {}",
                limit.operation(), clock.instant().plus(overrun.untilWindowEnds())));
        if (perIp.isEmpty() && global.isEmpty()) {
            return;
        }
        String solved = token.filter(candidate -> !candidate.isBlank() && candidate.length() <= MAX_TOKEN_LENGTH)
                .orElseThrow(CaptchaRequiredException::new);
        if (!turnstile.accepts(solved, clientIp)) {
            throw new CaptchaRequiredException();
        }
    }
}
