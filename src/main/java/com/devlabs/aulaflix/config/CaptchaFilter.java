package com.devlabs.aulaflix.config;

import java.io.IOException;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.devlabs.aulaflix.exception.CaptchaRequiredException;
import com.devlabs.aulaflix.exception.CaptchaUnavailableException;
import com.devlabs.aulaflix.service.CaptchaGate;
import com.devlabs.aulaflix.service.SoftLimit;

/**
 * Counts each request it matches against its operation's soft limits, and past them lets it through only with the
 * CAPTCHA token the BFF forwards in {@value #TOKEN_HEADER}. It runs after the hard limits, which count every request,
 * those with a solved CAPTCHA included, so that a request past a hard limit is refused as such whatever it carries.
 */
final class CaptchaFilter extends OncePerRequestFilter {

    static final String TOKEN_HEADER = "AulaFlix-Captcha-Token";

    private final RequestMatcher requests;
    private final CaptchaGate gate;
    private final SoftLimit limit;
    private final HandlerExceptionResolver problems;

    CaptchaFilter(RequestMatcher requests, CaptchaGate gate, SoftLimit limit, HandlerExceptionResolver problems) {
        this.requests = requests;
        this.gate = gate;
        this.limit = limit;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !requests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            gate.pass(limit, BffRequestFilter.clientIpOf(request),
                    Optional.ofNullable(request.getHeader(TOKEN_HEADER)));
        } catch (CaptchaRequiredException | CaptchaUnavailableException refusal) {
            problems.resolveException(request, response, null, refusal);
            return;
        }
        chain.doFilter(request, response);
    }
}
