package com.devlabs.aulaflix.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The storage of Lesson videos, read from {@code aulaflix.storage.*}: its two endpoints, the bucket, and the two keys,
 * which are secret files named after their properties. Without any of them, the API refuses to start.
 */
@Validated
@ConfigurationProperties("aulaflix.storage")
public record StorageProperties(
        /** Where the API itself reads and deletes: {@code http://storage:9000} beside it in Compose. */
        @NotNull
        URI internalEndpoint,

        /** The hostname every signed URL names, which the browser and the Admin's curl reach. */
        @NotNull
        URI publicEndpoint,

        @NotBlank
        String bucket,

        /** The region the signatures name; a single-node storage answers for one. */
        @NotBlank
        String region,

        /** How long a playback URL plays; SigV4 signs for 7 days at most. */
        @NotNull
        @DurationMin(seconds = 1)
        @DurationMax(days = 7)
        Duration playbackUrlLifetime,

        /** Signs uploads, and serves the API's own reads and deletes. */
        @NotNull
        @Valid
        Key readWrite,

        /** Signs playback, and nothing else. */
        @NotNull
        @Valid
        Key readOnly) {

    public record Key(
            @NotBlank
            String accessKeyId,
            @NotBlank
            String secretAccessKey) {

        /** Never shows the secret, wherever the properties end up printed. */
        @Override
        public String toString() {
            return "Key[accessKeyId=" + accessKeyId + "]";
        }
    }
}
