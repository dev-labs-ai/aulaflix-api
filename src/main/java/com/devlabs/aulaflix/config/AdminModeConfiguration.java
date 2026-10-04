package com.devlabs.aulaflix.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** What changes when the jar runs with {@code admin} as its first argument; see application-admin.properties. */
@Configuration(proxyBeanMethods = false)
@Profile(AdminModeConfiguration.PROFILE)
public class AdminModeConfiguration {

    public static final String PROFILE = "admin";

    /** Only the API migrates; the admin command checks the schema itself and refuses while it lags behind. */
    @Bean
    FlywayMigrationStrategy neverMigrate() {
        return flyway -> {
        };
    }
}
