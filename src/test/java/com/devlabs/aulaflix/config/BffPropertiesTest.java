package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Without a key worth the name, the API refuses to start rather than let anyone around the BFF. */
class BffPropertiesTest {

    private final ApplicationContextRunner application =
            new ApplicationContextRunner().withUserConfiguration(BffKeyRequired.class);

    @Test
    void refusesToStartWithoutTheKey() {
        application.run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.bff.key=", "aulaflix.bff.key=   ",
            "aulaflix.bff.key=0123456789abcdefghijklmnopqrstu"})
    void refusesToStartWithABlankOrShortKey(String property) {
        application.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWithAKeyOf32Characters() {
        application.withPropertyValues("aulaflix.bff.key=0123456789abcdefghijklmnopqrstuv")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(BffProperties.class).extracting(BffProperties::key)
                        .isEqualTo("0123456789abcdefghijklmnopqrstuv"));
    }

    @EnableConfigurationProperties(BffProperties.class)
    static class BffKeyRequired {
    }
}
