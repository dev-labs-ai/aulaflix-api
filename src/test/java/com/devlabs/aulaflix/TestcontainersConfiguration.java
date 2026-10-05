package com.devlabs.aulaflix;

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
