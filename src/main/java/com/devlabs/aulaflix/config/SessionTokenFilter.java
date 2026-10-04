package com.devlabs.aulaflix.config;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.service.SessionService;

/**
 * Authenticates a request by its session token. A request without one goes on anonymously, and the chain decides
 * whether it needs one; a token that is sent must be valid, wherever it is sent.
 */
final class SessionTokenFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Bearer";
    /** RFC 6750's b64token after the case-insensitive scheme. */
    private static final Pattern BEARER =
            Pattern.compile("^Bearer (?<token>[A-Za-z0-9._~+/-]+=*)$", Pattern.CASE_INSENSITIVE);

    private final SessionService sessions;
    private final AuthenticationEntryPoint unauthenticated;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    SessionTokenFilter(SessionService sessions, AuthenticationEntryPoint unauthenticated) {
        this.sessions = sessions;
        this.unauthenticated = unauthenticated;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())) {
            chain.doFilter(request, response);
            return;
        }
        Optional<AuthenticatedAccount> account = token(authorization).flatMap(sessions::resolve);
        if (account.isEmpty()) {
            unauthenticated.commence(request, response,
                    new BadCredentialsException("The session token is malformed, unknown, expired or revoked"));
            return;
        }
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(authentication(account.get()));
        contexts.setContext(context);
        chain.doFilter(request, response);
    }

    private static Optional<String> token(String authorization) {
        Matcher bearer = BEARER.matcher(authorization);
        return bearer.matches() ? Optional.of(bearer.group("token")) : Optional.empty();
    }

    private static UsernamePasswordAuthenticationToken authentication(AuthenticatedAccount account) {
        return UsernamePasswordAuthenticationToken.authenticated(
                account, null, List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
    }
}
