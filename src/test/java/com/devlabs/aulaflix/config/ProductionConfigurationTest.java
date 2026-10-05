package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * The production profile, which the production Compose file turns on, against #18's per-environment table. Its
 * secrets are files on the VPS, so each test supplies the storage keys itself, and the profile must hold none.
 */
class ProductionConfigurationTest {

    private final ApplicationContextRunner production = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(StorageRequired.class)
            .withPropertyValues(
                    "spring.profiles.active=production",
                    "aulaflix.storage.read-write.access-key-id=rw-key",
                    "aulaflix.storage.read-write.secret-access-key=rw-secret-access-key-of-the-test",
                    "aulaflix.storage.read-only.access-key-id=ro-key",
                    "aulaflix.storage.read-only.secret-access-key=ro-secret-access-key-of-the-test");

    @Test
    void signsUrlsForTheMediaHostnameAndReadsFromTheStorageBesideIt() {
        production.run(context -> {
            assertThat(context).hasNotFailed();
            StorageProperties storage = context.getBean(StorageProperties.class);
            assertThat(storage.publicEndpoint()).isEqualTo(URI.create("https://media.aulaflix.com.br"));
            assertThat(storage.internalEndpoint()).isEqualTo(URI.create("http://storage:9000"));
            assertThat(storage.bucket()).isEqualTo("videos");
            assertThat(storage.playbackUrlLifetime()).isEqualTo(Duration.ofHours(4));
        });
    }

    @Test
    void connectsToThePostgresBesideIt() {
        production.run(context -> assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:postgresql://postgres:5432/aulaflix"));
    }

    @Test
    void pointsEveryLinkAtTheProductionWeb() {
        production.run(context -> assertThat(context.getEnvironment().getProperty("aulaflix.web.base-url"))
                .isEqualTo("https://aulaflix.com.br"));
    }

    @Test
    void callsTheProductionAsaas() {
        production.run(context -> assertThat(context.getEnvironment().getProperty("aulaflix.asaas.base-url"))
                .isEqualTo("https://api.asaas.com/v3"));
    }

    @Test
    void verifiesCaptchasAtCloudflareWithinThreeSeconds() {
        production.run(context -> {
            ConfigurableEnvironment environment = context.getEnvironment();
            assertThat(environment.getProperty("aulaflix.turnstile.base-url"))
                    .isEqualTo("https://challenges.cloudflare.com/turnstile/v0");
            assertThat(environment.getProperty("aulaflix.turnstile.timeout")).isEqualTo("3s");
        });
    }

    @Test
    void sendsThroughSesOnPort587WithStartTlsRequired() {
        production.run(context -> {
            ConfigurableEnvironment environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.mail.host")).isEqualTo("email-smtp.sa-east-1.amazonaws.com");
            assertThat(environment.getProperty("spring.mail.port", Integer.class)).isEqualTo(587);
            assertThat(environment.getProperty("spring.mail.properties.mail.smtp.auth", Boolean.class)).isTrue();
            assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable", Boolean.class))
                    .isTrue();
            assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.required", Boolean.class))
                    .isTrue();
            assertThat(environment.getProperty("aulaflix.outbox.from")).isEqualTo("AulaFlix <contato@aulaflix.com.br>");
        });
    }

    /** One email a second is SES's sandbox rate, the lowest any account has, so production stays below its maximum. */
    @Test
    void drainsTheOutboxNoFasterThanOneEmailASecond() {
        production.run(context -> {
            ConfigurableEnvironment environment = context.getEnvironment();
            int emails = environment.getRequiredProperty("aulaflix.outbox.send-rate.emails", Integer.class);
            Duration per =
                    DurationStyle.detectAndParse(environment.getRequiredProperty("aulaflix.outbox.send-rate.per"));
            assertThat(per.dividedBy(emails)).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        });
    }

    @Test
    void holdsNoSecret() {
        production.run(context -> {
            List<String> names = context.getEnvironment().getPropertySources().stream()
                    .filter(source -> source.getName().contains("application-production.properties"))
                    .map(EnumerablePropertySource.class::cast)
                    .flatMap(source -> Arrays.stream(source.getPropertyNames()))
                    .toList();
            assertThat(names).isNotEmpty()
                    .noneMatch(name -> name.matches("(?i).*(password|secret|key|token|license|username).*"));
        });
    }

    @EnableConfigurationProperties(StorageProperties.class)
    static class StorageRequired {
    }
}
