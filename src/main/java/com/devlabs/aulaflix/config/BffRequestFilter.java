package com.devlabs.aulaflix.config;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

/**
 * Lets through only what the BFF sends: its key, then the browser's IP, which is read only once the key shows the BFF
 * sent it. A request it should not see, the Admin's through the SSH tunnel, is never filtered.
 */
final class BffRequestFilter extends OncePerRequestFilter {

    static final String KEY_HEADER = "AulaFlix-BFF-Key";
    static final String CLIENT_IP_HEADER = "AulaFlix-Client-IP";

    /**
     * Four decimal parts without leading zeros. The JDK also reads {@code 127.1} or {@code 017.0.0.1} as addresses, but
     * the BFF never writes them, so they are taken as malformed rather than guessed at.
     */
    private static final Pattern IPV4 = Pattern.compile("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}");

    /** Hexadecimal groups, perhaps ending in an IPv4 address, without the brackets of a URL or a zone. */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f.]*:[0-9A-Fa-f:.]*");

    private final RequestMatcher bffRequests;
    private final byte[] key;
    private final HandlerExceptionResolver problems;

    BffRequestFilter(RequestMatcher bffRequests, String key, HandlerExceptionResolver problems) {
        this.bffRequests = bffRequests;
        this.key = key.getBytes(StandardCharsets.UTF_8);
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
        if (!carriesAClientIp(request)) {
            problems.resolveException(request, response, null, new InvalidClientIpException());
            return;
        }
        chain.doFilter(request, response);
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
    private static boolean carriesAClientIp(HttpServletRequest request) {
        String clientIp = request.getHeader(CLIENT_IP_HEADER);
        if (clientIp == null || !(IPV4.matcher(clientIp).matches() || IPV6.matcher(clientIp).matches())) {
            return false;
        }
        try {
            InetAddress.ofLiteral(clientIp);
            return true;
        } catch (IllegalArgumentException notAnAddress) {
            return false;
        }
    }
}
