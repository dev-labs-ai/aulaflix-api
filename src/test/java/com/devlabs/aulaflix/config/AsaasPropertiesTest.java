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
 * Asaas's sandbox, timeout, Retry-After and the webhook worker's interval have local defaults; its API key and the
 * webhook's token are secrets with none. Each test sets both itself, so secret files a developer keeps in
 * {@code secrets/} change nothing here.
 */
class AsaasPropertiesTest {

    private static final String API_KEY = "$aact_hmlg_key-of-the-test";
    private static final String WEBHOOK_TOKEN = "webhook-token-of-the-test-0123456";

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(AsaasRequired.class)
            .withPropertyValues("aulaflix.asaas.api-key=" + API_KEY, "aulaflix.asaas.webhook-token=" + WEBHOOK_TOKEN);

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

    @Test
    void takesTheWebhookTokenAndRunsTheWorkerEveryFiveSecondsByDefault() {
        application.run(context -> {
            AsaasProperties asaas = context.getBean(AsaasProperties.class);
            assertThat(asaas.webhookToken()).isEqualTo(WEBHOOK_TOKEN);
            assertThat(asaas.webhookInterval()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    /** Reconciliation leaves an Order a few minutes for its webhook to come first. */
    @Test
    void expiresOrdersEveryMinuteAndReconcilesEveryTwoThoseAwaitingPaymentForFiveByDefault() {
        application.run(context -> {
            AsaasProperties asaas = context.getBean(AsaasProperties.class);
            assertThat(asaas.expiryInterval()).isEqualTo(Duration.ofMinutes(1));
            assertThat(asaas.reconciliationInterval()).isEqualTo(Duration.ofMinutes(2));
            assertThat(asaas.reconciliationDelay()).isEqualTo(Duration.ofMinutes(5));
        });
    }

    /** A paid Order's charge is re-read a few times a day, well within Asaas's quota. */
    @Test
    void reReadsEachPaidOrdersChargeEverySixHoursByDefault() {
        application.run(context -> assertThat(context.getBean(AsaasProperties.class).paidRecheckInterval())
                .isEqualTo(Duration.ofHours(6)));
    }

    /** Asaas takes a token of 32 to 255 characters. */
    @ParameterizedTest
    @ValueSource(ints = {32, 255})
    void takesAWebhookTokenAsLongAsAsaasTakes(int length) {
        application.withPropertyValues("aulaflix.asaas.webhook-token=" + "t".repeat(length))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.asaas.api-key=", "aulaflix.asaas.api-key= ", "aulaflix.asaas.base-url=",
            "aulaflix.asaas.timeout=0s", "aulaflix.asaas.retry-after=999ms", "aulaflix.asaas.webhook-token=",
            "aulaflix.asaas.webhook-token=                                ",
            "aulaflix.asaas.webhook-token=0123456789012345678901234567890", "aulaflix.asaas.webhook-interval=0s",
            "aulaflix.asaas.expiry-interval=0s", "aulaflix.asaas.reconciliation-interval=0s",
            "aulaflix.asaas.reconciliation-delay=999ms", "aulaflix.asaas.paid-recheck-interval=59s",
            "aulaflix.asaas.paid-recheck-interval="})
    void refusesToStartWithoutAnyOfItsSettings(String setting) {
        application.withPropertyValues(setting).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesAWebhookTokenLongerThanAsaasTakes() {
        application.withPropertyValues("aulaflix.asaas.webhook-token=" + "t".repeat(256))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void neverShowsTheKeyNorTheTokenWhenPrinted() {
        application.run(context -> assertThat(context.getBean(AsaasProperties.class).toString())
                .contains("api-sandbox.asaas.com", "PT5S")
                .doesNotContain(API_KEY, WEBHOOK_TOKEN));
    }

    @EnableConfigurationProperties(AsaasProperties.class)
    static class AsaasRequired {
    }
}
