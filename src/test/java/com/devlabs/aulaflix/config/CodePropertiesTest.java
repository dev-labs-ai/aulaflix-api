package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Without an HMAC key worth the name, the API refuses to start, rather than store codes that reverse offline. */
class CodePropertiesTest {

    private static final String KEY = "0123456789abcdefghijklmnopqrstuv";

    private final ApplicationContextRunner application =
            new ApplicationContextRunner().withUserConfiguration(HmacKeyRequired.class);

    @Test
    void refusesToStartWithoutTheKey() {
        application.run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.codes.hmac-key=", "aulaflix.codes.hmac-key=   ",
            "aulaflix.codes.hmac-key=0123456789abcdefghijklmnopqrstu"})
    void refusesToStartWithABlankOrShortKey(String property) {
        application.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWithAKeyOf32CharactersAndNeverPrintsIt() {
        application.withPropertyValues("aulaflix.codes.hmac-key=" + KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CodeProperties codes = context.getBean(CodeProperties.class);
                    assertThat(codes.hmacKey()).isEqualTo(KEY);
                    assertThat(codes.toString()).doesNotContain(KEY);
                });
    }

    /** The secret file {@code openssl rand -base64 32} writes ends in a newline, which is not part of the key. */
    @Test
    void readsTheKeyFromTheSecretFileNamedAfterIt(@TempDir Path secrets) throws IOException {
        Files.writeString(secrets.resolve("aulaflix.codes.hmac-key"), KEY + "\n");

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(HmacKeyRequired.class)
                .withPropertyValues("spring.config.import=configtree:" + secrets.toAbsolutePath() + "/")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(CodeProperties.class).extracting(CodeProperties::hmacKey).isEqualTo(KEY));
    }

    @EnableConfigurationProperties(CodeProperties.class)
    static class HmacKeyRequired {
    }
}
