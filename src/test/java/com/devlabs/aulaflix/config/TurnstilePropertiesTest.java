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
 * Turnstile's {@code siteverify}, its timeout and the Retry-After have defaults; its secret key is a secret with none.
 * Each test sets the key itself, so a secret file a developer keeps in {@code secrets/} changes nothing here.
 */
class TurnstilePropertiesTest {

    private static final String SECRET_KEY = "0x4AAAAAAA-secret-of-the-test";

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TurnstileRequired.class)
            .withPropertyValues("aulaflix.turnstile.secret-key=" + SECRET_KEY);

    @Test
    void verifiesAtCloudflareWaitingThreeSecondsAndAsksForARetryAfterTenByDefault() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            TurnstileProperties turnstile = context.getBean(TurnstileProperties.class);
            assertThat(turnstile.baseUrl()).isEqualTo(URI.create("https://challenges.cloudflare.com/turnstile/v0"));
            assertThat(turnstile.secretKey()).isEqualTo(SECRET_KEY);
            assertThat(turnstile.timeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(turnstile.retryAfter()).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.turnstile.secret-key=", "aulaflix.turnstile.secret-key= ",
            "aulaflix.turnstile.base-url=", "aulaflix.turnstile.timeout=0s", "aulaflix.turnstile.retry-after=999ms"})
    void refusesToStartWithoutAnyOfItsSettings(String setting) {
        application.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void neverShowsTheSecretKeyWhenPrinted() {
        application.run(context -> assertThat(context.getBean(TurnstileProperties.class).toString())
                .contains("challenges.cloudflare.com", "PT3S", "PT10S")
                .doesNotContain(SECRET_KEY));
    }

    @EnableConfigurationProperties(TurnstileProperties.class)
    static class TurnstileRequired {
    }
}
