package com.devlabs.aulaflix.config;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.devlabs.aulaflix.service.AsaasGateway;
import com.devlabs.aulaflix.service.CheckoutLimits;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AsaasProperties.class, RateLimitProperties.class})
public class PaymentConfiguration {

    /** Asaas wants a User-Agent from every account created since mid-2024. */
    private static final String USER_AGENT = "aulaflix-api";

    /**
     * The key goes in Asaas's {@code access_token} header. The JDK's client, with both timeouts set: Spring's defaults
     * wait forever. HTTP/1.1, since the JDK's attempt to upgrade a plain-HTTP POST to HTTP/2 is cancelled by servers
     * that take the upgrade, such as the tests' stub; HTTPS to Asaas gains nothing from HTTP/2 for a call at a time.
     */
    @Bean
    AsaasGateway asaasGateway(AsaasProperties asaas) {
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(asaas.timeout())
                        .build());
        requests.setReadTimeout(asaas.timeout());
        return new AsaasGateway(RestClient.builder()
                .baseUrl(asaas.baseUrl().toString())
                .requestFactory(requests)
                .defaultHeader("access_token", asaas.apiKey())
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build(), asaas.retryAfter());
    }

    @Bean
    CheckoutLimits checkoutLimits(RateLimitProperties limits) {
        return new CheckoutLimits(limits.checkoutPerStudentLimit(), limits.checkoutLimit());
    }
}
