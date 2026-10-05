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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.devlabs.aulaflix.service.RateLimiter;
import com.devlabs.aulaflix.service.SessionService;

/**
 * Deny by default. Every request but the Admin's comes from the BFF and carries its key; every Admin endpoint needs an
 * Admin session, here and again in its own {@code @PreAuthorize}. Playback needs no session, and refuses only an
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
                .addFilterAfter(new VisitorLimitFilter(PathPatternRequestMatcher.pathPattern(HttpMethod.GET, PLAYBACK),
                        limiter, limits.visitorPlaybackLimit(), resolver), SessionTokenFilter.class)
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/v1/admin/sessions").permitAll()
                        .requestMatchers(ADMIN).hasRole("ADMIN")
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
