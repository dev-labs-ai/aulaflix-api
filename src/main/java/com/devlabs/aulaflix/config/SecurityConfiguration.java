package com.devlabs.aulaflix.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.devlabs.aulaflix.service.SessionService;

/**
 * Deny by default. Every Admin endpoint needs an Admin session, here and again in its own {@code @PreAuthorize}. The
 * refusals the filters make go through the same {@code @RestControllerAdvice} as every other refusal.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiFilterChain(HttpSecurity http, SessionService sessions,
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
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/v1/admin/sessions").permitAll()
                        .requestMatchers("/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problems)
                        .accessDeniedHandler(problems))
                .build();
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
