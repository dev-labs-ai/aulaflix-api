package com.devlabs.aulaflix.config;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.devlabs.aulaflix.exception.InvalidBffKeyException;
import com.devlabs.aulaflix.exception.InvalidClientIpException;
import com.devlabs.aulaflix.exception.RateLimitedException;
import com.devlabs.aulaflix.service.RateLimit;
import com.devlabs.aulaflix.service.RateLimitKey;
import com.devlabs.aulaflix.service.RateLimiter;

/**
 * Lets through only what the BFF sends: its key, then the browser's IP, which is read only once the key shows the BFF
 * sent it, and counts the request against that IP's general limit. The IP then goes along with the request, for the
 * limits further down the chain. A request it should not see, the Admin's through the SSH tunnel, is never filtered,
 * so never counted.
 */
final class BffRequestFilter extends OncePerRequestFilter {

    static final String KEY_HEADER = "AulaFlix-BFF-Key";
    static final String CLIENT_IP_HEADER = "AulaFlix-Client-IP";

    private static final String CLIENT_IP_ATTRIBUTE = BffRequestFilter.class.getName() + ".clientIp";

    /**
     * Four decimal parts without leading zeros. The JDK also reads {@code 127.1} or {@code 017.0.0.1} as addresses, but
     * the BFF never writes them, so they are taken as malformed rather than guessed at.
     */
    private static final Pattern IPV4 = Pattern.compile("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}");

    /** Hexadecimal groups, perhaps ending in an IPv4 address, without the brackets of a URL or a zone. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*");

    private final RequestMatcher bffRequests;
    private final byte[] key;
    private final RateLimiter limiter;
    private final RateLimit bffRequestLimit;
    private final HandlerExceptionResolver problems;

    BffRequestFilter(RequestMatcher bffRequests, String key, RateLimiter limiter, RateLimit bffRequestLimit,
                     HandlerExceptionResolver problems) {
        this.bffRequests = bffRequests;
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.limiter = limiter;
        this.bffRequestLimit = bffRequestLimit;
        this.problems = problems;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !bffRequests.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!carriesTheKey(request)) {
            problems.resolveException(request, response, null, new InvalidBffKeyException());
            return;
        }
        Optional<InetAddress> clientIp = clientIp(request);
        if (clientIp.isEmpty()) {
            problems.resolveException(request, response, null, new InvalidClientIpException());
            return;
        }
        try {
            limiter.consume(bffRequestLimit, RateLimitKey.clientIp(clientIp.get()));
        } catch (RateLimitedException refusal) {
            problems.resolveException(request, response, null, refusal);
            return;
        }
        request.setAttribute(CLIENT_IP_ATTRIBUTE, clientIp.get());
        chain.doFilter(request, response);
    }

    /** The browser's IP, which a request this filter let through carries along. */
    static InetAddress clientIpOf(HttpServletRequest request) {
        return (InetAddress) request.getAttribute(CLIENT_IP_ATTRIBUTE);
    }

    /**
     * The key goes first: {@link MessageDigest#isEqual} then takes a time that depends on its length alone, so a guess
     * learns nothing about the key, not even how long it is.
     */
    private boolean carriesTheKey(HttpServletRequest request) {
        String sent = request.getHeader(KEY_HEADER);
        return sent != null && MessageDigest.isEqual(key, sent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * An IPv4 or IPv6 address as the BFF writes it, read as a literal so that no name is ever looked up. Without it
     * every visitor would fall into the bucket of the BFF's own address.
     */
    private static Optional<InetAddress> clientIp(HttpServletRequest request) {
        String clientIp = request.getHeader(CLIENT_IP_HEADER);
        if (clientIp == null || !(IPV4.matcher(clientIp).matches() || IPV6.matcher(clientIp).matches())) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(clientIp));
        } catch (IllegalArgumentException notAnAddress) {
            return Optional.empty();
        }
    }
}
