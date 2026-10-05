package com.devlabs.aulaflix.config;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.devlabs.aulaflix.service.CaptchaGate;
import com.devlabs.aulaflix.service.RateLimiter;
import com.devlabs.aulaflix.service.TurnstileVerifier;

/** The CAPTCHA past the soft limits, which only the web's requests meet: admin mode needs no Turnstile secret. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(TurnstileProperties.class)
public class CaptchaConfiguration {

    /**
     * The JDK's client, with both timeouts set: Spring's defaults wait forever, and a silent {@code siteverify} must
     * give the visitor a 503 after the timeout. HTTP/1.1, like Asaas's, for a call at a time.
     */
    @Bean
    CaptchaGate captchaGate(RateLimiter limiter, TurnstileProperties turnstile, Clock clock) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(turnstile.timeout())
                        .build());
        requests.setReadTimeout(turnstile.timeout());
        RestClient siteverify = RestClient.builder()
                .baseUrl(turnstile.baseUrl().toString())
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
        return new CaptchaGate(limiter, new TurnstileVerifier(siteverify, turnstile.secretKey(),
                turnstile.retryAfter()), clock);
    }
}
