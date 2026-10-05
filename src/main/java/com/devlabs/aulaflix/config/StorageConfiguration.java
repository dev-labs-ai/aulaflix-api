package com.devlabs.aulaflix.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.devlabs.aulaflix.service.VideoStorage;

/**
 * The storage is reached with path-style URLs ({@code /videos/<key>}), since its one hostname serves one bucket, and
 * through nothing but the S3 API, so that the server can change without touching code.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfiguration {

    /** A request waits at most this long, a header read included, before the Admin gets a 500 instead. */
    private static final Duration API_CALL_TIMEOUT = Duration.ofMinutes(1);

    @Bean
    VideoStorage videoStorage(StorageProperties storage) {
        return new VideoStorage(client(storage), signer(storage, storage.readWrite()),
                signer(storage, storage.readOnly()), storage.bucket(), storage.playbackUrlLifetime());
    }

    /**
     * Checksums only where the S3 API requires them: the SDK's default adds them to every request, which an
     * S3-compatible server need not accept.
     */
    private static S3Client client(StorageProperties storage) {
        return S3Client.builder()
                .endpointOverride(storage.internalEndpoint())
                .region(Region.of(storage.region()))
                .forcePathStyle(true)
                .credentialsProvider(credentials(storage.readWrite()))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(override -> override.apiCallTimeout(API_CALL_TIMEOUT))
                .build();
    }

    private static S3Presigner signer(StorageProperties storage, StorageProperties.Key key) {
        return S3Presigner.builder()
                .endpointOverride(storage.publicEndpoint())
                .region(Region.of(storage.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials(key))
                .build();
    }

    private static StaticCredentialsProvider credentials(StorageProperties.Key key) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(key.accessKeyId(), key.secretAccessKey()));
    }
}
