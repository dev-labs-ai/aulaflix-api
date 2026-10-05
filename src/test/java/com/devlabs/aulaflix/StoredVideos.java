package com.devlabs.aulaflix;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Reaches the videos bucket as the storage's root, beside the API: tests put uploads in place with it, as an Admin's
 * curl would, and read what the API left in storage.
 */
public final class StoredVideos implements AutoCloseable {

    private static final String BUCKET = "videos";

    private final S3Client client;

    public StoredVideos(AistorContainer storage) {
        this.client = S3Client.builder()
                .endpointOverride(URI.create(storage.endpoint()))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.rootUser(), storage.rootPassword())))
                .build();
    }

    public void put(String key, byte[] content) {
        client.putObject(request -> request.bucket(BUCKET).key(key).contentType("video/mp4"),
                RequestBody.fromBytes(content));
    }

    /** Every key under the Lesson's prefix, in order. */
    public List<String> keysOf(long lessonId) {
        String prefix = "lessons/%d/".formatted(lessonId);
        return client.listObjectsV2Paginator(request -> request.bucket(BUCKET).prefix(prefix))
                .contents().stream()
                .map(S3Object::key)
                .sorted()
                .toList();
    }

    /**
     * A tiny faststart H.264/AAC MP4 from {@code src/test/resources/videos}, made with ffmpeg from its test source and
     * a sine: {@code -t <seconds> -c:v libx264 -profile:v baseline -c:a aac -movflags +faststart}.
     */
    public static byte[] fixture(String name) {
        try (InputStream in = StoredVideos.class.getResourceAsStream("/videos/" + name)) {
            return in.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
