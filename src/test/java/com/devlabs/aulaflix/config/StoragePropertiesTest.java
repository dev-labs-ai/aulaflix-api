package com.devlabs.aulaflix.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The storage's endpoints, bucket and playback lifetime have local defaults; its keys are secrets with none. Each test
 * sets the keys itself, so a secret file a developer keeps in {@code secrets/} changes nothing here.
 */
class StoragePropertiesTest {

    private static final String READ_WRITE_SECRET = "rw-secret-access-key-of-the-test";

    private final ApplicationContextRunner application = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(StorageRequired.class)
            .withPropertyValues(
                    "aulaflix.storage.read-write.access-key-id=rw-key",
                    "aulaflix.storage.read-write.secret-access-key=" + READ_WRITE_SECRET,
                    "aulaflix.storage.read-only.access-key-id=ro-key",
                    "aulaflix.storage.read-only.secret-access-key=ro-secret-access-key-of-the-test");

    @Test
    void pointsAtTheLocalStorageAndPlaysForFourHoursByDefault() {
        application.run(context -> {
            assertThat(context).hasNotFailed();
            StorageProperties storage = context.getBean(StorageProperties.class);
            assertThat(storage.internalEndpoint()).isEqualTo(URI.create("http://localhost:9000"));
            assertThat(storage.publicEndpoint()).isEqualTo(URI.create("http://localhost:9000"));
            assertThat(storage.bucket()).isEqualTo("videos");
            assertThat(storage.region()).isEqualTo("us-east-1");
            assertThat(storage.playbackUrlLifetime()).isEqualTo(Duration.ofHours(4));
            assertThat(storage.readWrite()).isEqualTo(new StorageProperties.Key("rw-key", READ_WRITE_SECRET));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"aulaflix.storage.read-write.access-key-id=",
            "aulaflix.storage.read-write.secret-access-key=", "aulaflix.storage.read-only.access-key-id=",
            "aulaflix.storage.read-only.secret-access-key= ", "aulaflix.storage.internal-endpoint=",
            "aulaflix.storage.public-endpoint=", "aulaflix.storage.bucket=", "aulaflix.storage.region="})
    void refusesToStartWithoutAnyOfItsSettings(String blank) {
        application.withPropertyValues(blank).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1m", "999ms", "PT168H1S"})
    void refusesAPlaybackLifetimeAPresignedUrlCannotHave(String lifetime) {
        application.withPropertyValues("aulaflix.storage.playback-url-lifetime=" + lifetime)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void takesThePlaybackLifetimeFromConfigurationUpToSevenDays() {
        application.withPropertyValues("aulaflix.storage.playback-url-lifetime=7d")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(StorageProperties.class).extracting(StorageProperties::playbackUrlLifetime)
                        .isEqualTo(Duration.ofDays(7)));
    }

    @Test
    void neverShowsASecretKeyWhenPrinted() {
        application.run(context -> assertThat(context.getBean(StorageProperties.class).toString())
                .contains("rw-key")
                .doesNotContain(READ_WRITE_SECRET));
    }

    @EnableConfigurationProperties(StorageProperties.class)
    static class StorageRequired {
    }
}
