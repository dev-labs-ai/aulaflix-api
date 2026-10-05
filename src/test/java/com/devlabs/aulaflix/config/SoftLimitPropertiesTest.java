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
import com.devlabs.aulaflix.service.SoftLimit;

/**
 * Every soft limit is configuration, whose defaults are the numbers the spec gives. They count on counters apart from
 * the hard limits of the same operation.
 */
class SoftLimitPropertiesTest {

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(SoftLimitsRequired.class);

    @Test
    void asksForACaptchaPast10LookUpsAndSignInsPerIpIn15MinutesOr300FromEveryoneInAnHourByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(SoftLimitProperties.class).extracting(SoftLimitProperties::lookUpAndSignInLimit)
                .isEqualTo(new SoftLimit("look-ups-and-sign-ins",
                        new RateLimit("soft look-ups-and-sign-ins", 10, Duration.ofMinutes(15)),
                        new RateLimit("soft look-ups-and-sign-ins", 300, Duration.ofHours(1)))));
    }

    @Test
    void asksForACaptchaPast3SignUpsPerIpOr60FromEveryoneInAnHourByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(SoftLimitProperties.class).extracting(SoftLimitProperties::signUpLimit)
                .isEqualTo(new SoftLimit("sign-ups", new RateLimit("soft sign-ups", 3, Duration.ofHours(1)),
                        new RateLimit("soft sign-ups", 60, Duration.ofHours(1)))));
    }

    @Test
    void asksForACaptchaPast5ResetCodeRequestsPerIpOr60FromEveryoneInAnHourByDefault() {
        application.run(context -> assertThat(context).hasNotFailed()
                .getBean(SoftLimitProperties.class).extracting(SoftLimitProperties::passwordResetCodeLimit)
                .isEqualTo(new SoftLimit("password-reset-codes",
                        new RateLimit("soft password-reset-codes", 5, Duration.ofHours(1)),
                        new RateLimit("soft password-reset-codes", 60, Duration.ofHours(1)))));
    }

    @Test
    void takesOtherLimitsFromConfiguration() {
        application.withPropertyValues("aulaflix.soft-limits.sign-ups.per-ip.requests=7",
                        "aulaflix.soft-limits.sign-ups.per-ip.window=10m",
                        "aulaflix.soft-limits.sign-ups.global.requests=70",
                        "aulaflix.soft-limits.sign-ups.global.window=2h")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(SoftLimitProperties.class).extracting(SoftLimitProperties::signUpLimit)
                        .isEqualTo(new SoftLimit("sign-ups",
                                new RateLimit("soft sign-ups", 7, Duration.ofMinutes(10)),
                                new RateLimit("soft sign-ups", 70, Duration.ofHours(2)))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.soft-limits.look-ups-and-sign-ins.per-ip.requests=0",
            "aulaflix.soft-limits.look-ups-and-sign-ins.global.window=999ms",
            "aulaflix.soft-limits.sign-ups.per-ip.window=0s", "aulaflix.soft-limits.sign-ups.global.requests=0",
            "aulaflix.soft-limits.password-reset-codes.per-ip.requests=-1",
            "aulaflix.soft-limits.password-reset-codes.global.window=0s"})
    void refusesToStartWithALimitThatCountsNothing(String property) {
        application.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithoutTheLimits() {
        new ApplicationContextRunner().withUserConfiguration(SoftLimitsRequired.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(SoftLimitProperties.class)
    static class SoftLimitsRequired {
    }
}
