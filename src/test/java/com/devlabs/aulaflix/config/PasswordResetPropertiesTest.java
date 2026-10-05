package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** How long a reset-code request takes at least is configuration, far above the work a Student's email asks for. */
class PasswordResetPropertiesTest {

    @Test
    void makesEveryCodeRequestTake300MillisecondsByDefault() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(PasswordResetRequired.class)
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(PasswordResetProperties.class).extracting(PasswordResetProperties::codeRequestTime)
                        .isEqualTo(Duration.ofMillis(300)));
    }

    @Test
    void refusesToStartWithoutIt() {
        new ApplicationContextRunner().withUserConfiguration(PasswordResetRequired.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(PasswordResetProperties.class)
    static class PasswordResetRequired {
    }
}
