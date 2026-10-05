package com.devlabs.aulaflix.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.exception.RateLimitedException;
import com.devlabs.aulaflix.service.RateLimit;
import com.devlabs.aulaflix.service.RateLimitKey;
import com.devlabs.aulaflix.service.RateLimiter;

/**
 * Counts each request to the endpoint that comes without a session against its client IP, whatever the endpoint then
 * answers, and refuses it past the limit. It runs once the BFF's filter has read the client IP, and once the session
 * token is resolved: a request whose token is refused never gets here, but it gets nothing either, and the BFF then
 * asks again without the token, which counts.
 */
final class VisitorLimitFilter extends OncePerRequestFilter {

    private final RequestMatcher endpoint;
    private final RateLimiter limiter;
    private final RateLimit limit;
    private final HandlerExceptionResolver problems;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    VisitorLimitFilter(RequestMatcher endpoint, RateLimiter limiter, RateLimit limit,
                       HandlerExceptionResolver problems) {
        this.endpoint = endpoint;
        this.limiter = limiter;
        this.limit = limit;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !endpoint.matches(request) || hasASession();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            limiter.consume(limit, RateLimitKey.clientIp(BffRequestFilter.clientIpOf(request)));
        } catch (RateLimitedException refusal) {
            problems.resolveException(request, response, null, refusal);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean hasASession() {
        Authentication authentication = contexts.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedAccount;
    }
}
