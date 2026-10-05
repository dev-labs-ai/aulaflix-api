package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Asaas's sandbox, timeout and Retry-After have local defaults; its API key is a secret with none. Each test sets the
 * key itself, so a secret file a developer keeps in {@code secrets/} changes nothing here.
 */
class AsaasPropertiesTest {

    private static final String API_KEY = "$aact_hmlg_key-of-the-test";

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AsaasRequired.class)
            .withPropertyValues("aulaflix.asaas.api-key=" + API_KEY);

    @Test
    void callsTheSandboxWaitingTenSecondsAndAsksForARetryAfterThirtyByDefault() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            AsaasProperties asaas = context.getBean(AsaasProperties.class);
            assertThat(asaas.baseUrl()).isEqualTo(URI.create("https://api-sandbox.asaas.com/v3"));
            assertThat(asaas.apiKey()).isEqualTo(API_KEY);
            assertThat(asaas.timeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(asaas.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.asaas.api-key=", "aulaflix.asaas.api-key= ", "aulaflix.asaas.base-url=",
            "aulaflix.asaas.timeout=0s", "aulaflix.asaas.retry-after=999ms"})
    void refusesToStartWithoutAnyOfItsSettings(String setting) {
        application.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void neverShowsTheKeyWhenPrinted() {
        application.run(context -> assertThat(context.getBean(AsaasProperties.class).toString())
                .contains("api-sandbox.asaas.com")
                .doesNotContain(API_KEY));
    }

    @EnableConfigurationProperties(AsaasProperties.class)
    static class AsaasRequired {
    }
}
