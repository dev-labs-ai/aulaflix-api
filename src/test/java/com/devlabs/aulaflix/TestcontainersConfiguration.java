package com.devlabs.aulaflix;

import java.time.Duration;
import java.time.Instant;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Imported by {@link IntegrationTest}, and deliberately not a {@code @TestConfiguration}: the admin command tests
 * boot a plain {@code SpringApplication}, which component-scans the test classes without the test-type exclusion
 * and would pick this class up, starting a second container.
 */
public class TestcontainersConfiguration {

    private static final String POSTGRES_IMAGE = "postgres:18.6-alpine3.24";

    /**
     * How long the tests' application waits on a silent SMTP server: short, so that a test can outwait it, yet long
     * enough that Mailpit never misses it under the whole suite's load.
     */
    public static final Duration SMTP_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    @Bean
    AistorContainer storage() {
        return new AistorContainer();
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(AistorContainer storage) {
        return registry -> storage.applicationProperties().forEach(registry::add);
    }

    @Bean(destroyMethod = "stop")
    Hibp hibp() {
        return new Hibp();
    }

    @Bean
    DynamicPropertyRegistrar hibpProperties(Hibp hibp) {
        return registry -> hibp.applicationProperties().forEach(registry::add);
    }

    @Bean(destroyMethod = "stop")
    Asaas asaas() {
        return new Asaas();
    }

    @Bean
    DynamicPropertyRegistrar asaasProperties(Asaas asaas) {
        return registry -> asaas.applicationProperties().forEach(registry::add);
    }

    @Bean
    MailpitContainer mailpitContainer() {
        return new MailpitContainer();
    }

    @Bean(destroyMethod = "stop")
    SmtpRelay smtpRelay(MailpitContainer mailpit) {
        return new SmtpRelay(mailpit.getHost(), mailpit::smtpPort);
    }

    /** The API sends through the relay, and gives up on a silent server after {@link #SMTP_TIMEOUT}. */
    @Bean
    DynamicPropertyRegistrar mailProperties(SmtpRelay smtp) {
        return registry -> {
            registry.add("spring.mail.host", smtp::host);
            registry.add("spring.mail.port", smtp::port);
            String timeout = Long.toString(SMTP_TIMEOUT.toMillis());
            registry.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> timeout);
            registry.add("spring.mail.properties.mail.smtp.timeout", () -> timeout);
            registry.add("spring.mail.properties.mail.smtp.writetimeout", () -> timeout);
        };
    }

    @Bean
    Mailpit mailpit(MailpitContainer container) {
        return new Mailpit(container);
    }

    @Bean
    StoredVideos storedVideos(AistorContainer storage) {
        return new StoredVideos(storage);
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock(Instant.now());
    }
}
