package com.devlabs.aulaflix.config;

import java.util.Arrays;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.devlabs.aulaflix.dto.AuthenticatedAccount;
import com.devlabs.aulaflix.service.RateLimiter;
import com.devlabs.aulaflix.service.SessionService;

/**
 * Deny by default. Every request but the Admin's comes from the BFF and carries its key; every Admin endpoint needs an
 * Admin session, here and again in its own {@code @PreAuthorize}. The Student's own Account and session need a
 * Student's session, here and again in their {@code @PreAuthorize}, while looking up an email, signing up and signing
 * in need none, and count against the client IP's strictest limits; so does posting a confirmation link, which any
 * device may do. Playback needs no session, and refuses only an
 * Admin's, here and again in its {@code @PreAuthorize}. The refusals the filters make go through the same
 * {@code @RestControllerAdvice} as every other refusal.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({BffProperties.class, RateLimitProperties.class})
public class SecurityConfiguration {

    /** The OpenAPI document and Swagger UI, which the Admin reads through the SSH tunnel like its own endpoints. */
    private static final String[] DOCUMENTATION = {"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    private static final String ADMIN = "/v1/admin/**";

    private static final String PLAYBACK = "/v1/lessons/*/playback";

    private static final String LOOK_UPS = "/v1/account-lookups";

    private static final String SIGN_UPS = "/v1/accounts";

    private static final String SIGN_INS = "/v1/sessions";

    private static final String EMAIL_CONFIRMATIONS = "/v1/email-confirmations";

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, SessionService sessions, BffProperties bff,
                                       RateLimiter limiter, RateLimitProperties limits,
                                       @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        ProblemResponses problems = new ProblemResponses(resolver);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new SessionTokenFilter(sessions, problems), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new BffRequestFilter(bffRequests(), bff.key(), limiter, limits.bffRequestLimit(),
                        resolver), SessionTokenFilter.class)
                .addFilterAfter(new ClientIpLimitFilter(visitorPlayback(), limiter, limits.visitorPlaybackLimit(),
                        resolver), SessionTokenFilter.class)
                .addFilterAfter(new ClientIpLimitFilter(postsTo(LOOK_UPS, SIGN_INS), limiter,
                        limits.lookUpAndSignInLimit(), resolver), SessionTokenFilter.class)
                .addFilterAfter(new ClientIpLimitFilter(postsTo(SIGN_UPS), limiter, limits.signUpLimit(), resolver),
                        SessionTokenFilter.class)
                .addFilterAfter(new ClientIpLimitFilter(postsTo(EMAIL_CONFIRMATIONS), limiter,
                        limits.emailConfirmationLimit(), resolver), SessionTokenFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/v1/admin/sessions").permitAll()
                        .requestMatchers(ADMIN).hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, LOOK_UPS, SIGN_UPS, SIGN_INS, EMAIL_CONFIRMATIONS).permitAll()
                        .requestMatchers("/v1/account", "/v1/account/**", "/v1/sessions/current").hasRole("STUDENT")
                        .requestMatchers(HttpMethod.GET, "/v1/courses", "/v1/courses/*").permitAll()
                        .requestMatchers(HttpMethod.GET, PLAYBACK).not().hasRole("ADMIN")
                        .requestMatchers(DOCUMENTATION).permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .build();
    }

    /** Every request but the Admin's, which reaches the API only through the SSH tunnel. */
    private static RequestMatcher bffRequests() {
        Stream<String> adminPaths = Stream.concat(Stream.of(ADMIN), Arrays.stream(DOCUMENTATION));
        return new NegatedRequestMatcher(new OrRequestMatcher(
                adminPaths.<RequestMatcher>map(PathPatternRequestMatcher::pathPattern).toList()));
    }

    /**
     * Playback that comes without a session, whatever it answers; only the Free lesson plays without one. The session
     * token is resolved by the time the limit asks.
     */
    private static RequestMatcher visitorPlayback() {
        SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();
        RequestMatcher withoutASession = request -> {
            Authentication authentication = contexts.getContext().getAuthentication();
            return authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedAccount);
        };
        return new AndRequestMatcher(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, PLAYBACK), withoutASession);
    }

    private static RequestMatcher postsTo(String... paths) {
        return new OrRequestMatcher(Arrays.stream(paths)
                .<RequestMatcher>map(path -> PathPatternRequestMatcher.pathPattern(HttpMethod.POST, path))
                .toList());
    }

    /** Hands the 401s and 403s of the filters to the {@code @RestControllerAdvice}, like any other refusal. */
    private record ProblemResponses(HandlerExceptionResolver resolver)
            implements AuthenticationEntryPoint, AccessDeniedHandler {

        @Override
        public void commence(HttpServletRequest request, HttpServletResponse response,
                             AuthenticationException refusal) {
            resolver.resolveException(request, response, null, refusal);
        }

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException refusal) {
            resolver.resolveException(request, response, null, refusal);
        }
    }
}
