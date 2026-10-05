package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.devlabs.aulaflix.service.UnsubscribeTokens;

/**
 * The unsubscribe key is what {@code openssl rand -base64 32} writes: an AES-256 key in base64. Without one, the API
 * refuses to start, rather than send launch emails whose links it cannot open.
 */
class WaitlistPropertiesTest {

    private static final String KEY = "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s=";

    private final ApplicationContextRunner application =
            new ApplicationContextRunner().withUserConfiguration(WaitlistConfiguration.class);

    @Test
    void refusesToStartWithoutTheKey() {
        application.run(context -> assertThat(context).hasFailed());
    }

    /** Blank; 31 and 33 bytes; base64url's alphabet; a key in hex; padding missing. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hGw==",
            "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5sA", "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s-",
            "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2hG5s", "q3Lx0w9sVYbq0m2v5tP3dXkYJ8b1n6fA4cR7eU2h_5s=",
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"})
    void refusesToStartWithAKeyThatIsNotBase64Of32Bytes(String key) {
        application.withPropertyValues("aulaflix.waitlist.unsubscribe-key=" + key)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void startsWithA32ByteKeyAndNeverPrintsIt() {
        application.withPropertyValues("aulaflix.waitlist.unsubscribe-key=" + KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed().getBean(UnsubscribeTokens.class).isNotNull();
                    WaitlistProperties waitlist = context.getBean(WaitlistProperties.class);
                    assertThat(waitlist.unsubscribeKey()).isEqualTo(KEY);
                    assertThat(waitlist.toString()).isEqualTo("WaitlistProperties[unsubscribeKey=<hidden>]");
                });
    }

    /** The secret file {@code openssl rand -base64 32} writes ends in a newline, which is not part of the key. */
    @Test
    void readsTheKeyFromTheSecretFileNamedAfterIt(@TempDir Path secrets) throws IOException {
        Files.writeString(secrets.resolve("aulaflix.waitlist.unsubscribe-key"), KEY + "\n");

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(WaitlistConfiguration.class)
                .withPropertyValues("spring.config.import=configtree:" + secrets.toAbsolutePath() + "/")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(WaitlistProperties.class).extracting(WaitlistProperties::unsubscribeKey)
                        .isEqualTo(KEY));
    }
}
