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
    void allowsEachClientIp30PlaybacksWithoutASessionAnHourByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(RateLimitProperties.class).extracting(RateLimitProperties::visitorPlaybackLimit)
                .isEqualTo(new RateLimit("visitor-playback", 30, Duration.ofHours(1))));
    }

    @Test
    void allowsEachClientIp60LookUpsAndSignInsTogetherAnHourByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(RateLimitProperties.class).extracting(RateLimitProperties::lookUpAndSignInLimit)
                .isEqualTo(new RateLimit("look-ups-and-sign-ins", 60, Duration.ofHours(1))));
    }

    @Test
    void allowsEachClientIp10SignUpsADayByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(RateLimitProperties.class).extracting(RateLimitProperties::signUpLimit)
                .isEqualTo(new RateLimit("sign-ups", 10, Duration.ofDays(1))));
    }

    @Test
    void allowsEachStudent10PlacementsAnHourByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(RateLimitProperties.class).extracting(RateLimitProperties::checkoutPerStudentLimit)
                .isEqualTo(new RateLimit("checkouts-per-student", 10, Duration.ofHours(1))));
    }

    @Test
    void allowsEachClientIp30PlacementsAnHourByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(RateLimitProperties.class).extracting(RateLimitProperties::checkoutPerIpLimit)
                .isEqualTo(new RateLimit("checkouts-per-ip", 30, Duration.ofHours(1))));
    }

    @Test
    void allowsEveryone500PlacementsAnHourByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(RateLimitProperties.class).extracting(RateLimitProperties::checkoutLimit)
                .isEqualTo(new RateLimit("checkouts", 500, Duration.ofHours(1))));
    }

    @Test
    void takesLowerLimitsFromConfiguration() {
        application.withPropertyValues("aulaflix.rate-limits.bff-requests.requests=5",
                        "aulaflix.rate-limits.bff-requests.window=10s",
                        "aulaflix.rate-limits.visitor-playback.requests=3",
                        "aulaflix.rate-limits.visitor-playback.window=20s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RateLimitProperties limits = context.getBean(RateLimitProperties.class);
                    assertThat(limits.bffRequestLimit())
                            .isEqualTo(new RateLimit("bff-requests", 5, Duration.ofSeconds(10)));
                    assertThat(limits.visitorPlaybackLimit())
                            .isEqualTo(new RateLimit("visitor-playback", 3, Duration.ofSeconds(20)));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.rate-limits.bff-requests.requests=0",
            "aulaflix.rate-limits.bff-requests.requests=-1", "aulaflix.rate-limits.bff-requests.window=0s",
            "aulaflix.rate-limits.bff-requests.window=-1m", "aulaflix.rate-limits.bff-requests.window=999ms",
            "aulaflix.rate-limits.visitor-playback.requests=0", "aulaflix.rate-limits.visitor-playback.window=999ms",
            "aulaflix.rate-limits.look-ups-and-sign-ins.requests=0", "aulaflix.rate-limits.sign-ups.window=0s",
            "aulaflix.rate-limits.checkouts-per-student.requests=0", "aulaflix.rate-limits.checkouts-per-ip.window=0s",
            "aulaflix.rate-limits.checkouts.requests=0"})
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
