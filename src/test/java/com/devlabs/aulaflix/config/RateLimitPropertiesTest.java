package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.devlabs.aulaflix.service.RateLimit;

/** Every limit is configuration, whose defaults are the numbers the spec gives; a test may lower them. */
class RateLimitPropertiesTest {

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RateLimitsRequired.class);

    @Test
    void allowsEachClientIp600BffRequestsAMinuteByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(RateLimitProperties.class).extracting(RateLimitProperties::bffRequestLimit)
                .isEqualTo(new RateLimit("bff-requests", 600, Duration.ofMinutes(1))));
    }

    @Test
    void takesLowerLimitsFromConfiguration() {
        application.withPropertyValues("aulaflix.rate-limits.bff-requests.requests=5",
                        "aulaflix.rate-limits.bff-requests.window=10s")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(RateLimitProperties.class).extracting(RateLimitProperties::bffRequestLimit)
                        .isEqualTo(new RateLimit("bff-requests", 5, Duration.ofSeconds(10))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.rate-limits.bff-requests.requests=0",
            "aulaflix.rate-limits.bff-requests.requests=-1", "aulaflix.rate-limits.bff-requests.window=0s",
            "aulaflix.rate-limits.bff-requests.window=-1m", "aulaflix.rate-limits.bff-requests.window=999ms"})
    void refusesToStartWithALimitThatCountsNothing(String property) {
        application.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithoutTheLimits() {
        new ApplicationContextRunner().withUserConfiguration(RateLimitsRequired.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(RateLimitProperties.class)
    static class RateLimitsRequired {
    }
}
