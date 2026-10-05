package com.devlabs.aulaflix.config;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.devlabs.aulaflix.service.AsaasGateway;
import com.devlabs.aulaflix.service.AsaasUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The Asaas client the configuration builds, against a WireMock of its own: it calls the base URL with the key, and
 * stops waiting at the timeout, telling the caller when to retry.
 */
class PaymentConfigurationTest {

    private static final String API_KEY = "$aact_hmlg_key-of-the-test";
    private static final Duration TIMEOUT = Duration.ofMillis(500);
    private static final Duration RETRY_AFTER = Duration.ofSeconds(45);

    private final WireMockServer asaas = new WireMockServer(options().dynamicPort());

    private AsaasGateway gateway;

    @BeforeEach
    void buildTheClient() {
        asaas.start();
        gateway = new PaymentConfiguration().asaasGateway(new AsaasProperties(
                URI.create(asaas.baseUrl() + "/v3"), API_KEY, TIMEOUT, RETRY_AFTER,
                "webhook-token-of-the-test-0123456789", Duration.ofSeconds(5)));
    }

    @AfterEach
    void stopAsaas() {
        asaas.stop();
    }

    @Test
    void callsTheBaseUrlWithTheKeyAndAUserAgent() {
        asaas.stubFor(post(urlPathEqualTo("/v3/customers")).willReturn(okJson("{\"id\": \"cus_000005219613\"}")));

        assertThat(gateway.createCustomer("Bia", "52998224725")).isEqualTo("cus_000005219613");

        asaas.verify(postRequestedFor(urlPathEqualTo("/v3/customers"))
                .withHeader("access_token", equalTo(API_KEY))
                .withHeader("User-Agent", equalTo("aulaflix-api")));
    }

    @Test
    void stopsWaitingAtTheTimeoutAndSaysWhenToRetry() {
        asaas.stubFor(post(urlPathEqualTo("/v3/customers"))
                .willReturn(okJson("{\"id\": \"cus_000005219613\"}").withFixedDelay(2_000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> gateway.createCustomer("Bia", "52998224725"))
                .isInstanceOfSatisfying(AsaasUnavailableException.class,
                        failure -> assertThat(failure.retryAfter()).isEqualTo(RETRY_AFTER));
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1_500));
    }
}
