package com.devlabs.aulaflix.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.devlabs.aulaflix.exception.InvalidWebhookTokenException;

/**
 * Lets through to the webhook only what carries Asaas's token, in {@code asaas-access-token}: Asaas signs nothing, so
 * the token, and the edge's IP allow-list, are all that tell its deliveries apart. A refused delivery is never read,
 * so nothing of it is stored.
 */
final class WebhookTokenFilter extends OncePerRequestFilter {

    static final String TOKEN_HEADER = "asaas-access-token";

    private final RequestMatcher webhook;
    private final byte[] token;
    private final HandlerExceptionResolver problems;

    WebhookTokenFilter(RequestMatcher webhook, String token, HandlerExceptionResolver problems) {
        this.webhook = webhook;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !webhook.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!carriesTheToken(request)) {
            problems.resolveException(request, response, null, new InvalidWebhookTokenException());
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The token goes first: {@link MessageDigest#isEqual} then takes a time that depends on its length alone, so a guess
     * learns nothing about the token, not even how long it is.
     */
    private boolean carriesTheToken(HttpServletRequest request) {
        String sent = request.getHeader(TOKEN_HEADER);
        return sent != null && MessageDigest.isEqual(token, sent.getBytes(StandardCharsets.UTF_8));
    }
}
