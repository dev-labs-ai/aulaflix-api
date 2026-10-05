package com.devlabs.aulaflix.service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalLong;

import software.amazon.awssdk.awscore.presigner.PresignedRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The Video module's only way to the storage, through the S3 API alone (ADR 0003). The API's own reads and deletes go
 * to the internal endpoint with the read-write key. The URLs it signs name the public endpoint: an upload with the
 * read-write key, playback with the read-only one.
 */
public class VideoStorage implements AutoCloseable {

    static final String CONTENT_TYPE = "video/mp4";

    private static final Duration UPLOAD_URL_LIFETIME = Duration.ofHours(1);
    private static final int NOT_FOUND = 404;

    private final S3Client client;
    private final S3Presigner uploadSigner;
    private final S3Presigner playbackSigner;
    private final String bucket;
    private final Duration playbackUrlLifetime;

    public VideoStorage(S3Client client, S3Presigner uploadSigner, S3Presigner playbackSigner, String bucket,
                        Duration playbackUrlLifetime) {
        this.client = client;
        this.uploadSigner = uploadSigner;
        this.playbackSigner = playbackSigner;
        this.bucket = bucket;
        this.playbackUrlLifetime = playbackUrlLifetime;
    }

    /**
     * A PUT of the key, valid for an hour, with the content type signed in. It asks for no server-side encryption,
     * which AIStor Free refuses with a 405.
     */
    SignedUrl signUpload(String key) {
        return signed(uploadSigner.presignPutObject(request -> request
                .signatureDuration(UPLOAD_URL_LIFETIME)
                .putObjectRequest(object -> object.bucket(bucket).key(key).contentType(CONTENT_TYPE))));
    }

    /** A GET of the key, valid for the configured lifetime, which a plain {@code <video>} element can play. */
    SignedUrl signPlayback(String key) {
        return signed(playbackSigner.presignGetObject(request -> request
                .signatureDuration(playbackUrlLifetime)
                .getObjectRequest(object -> object.bucket(bucket).key(key))));
    }

    /** The object's size, or nothing when no object has the key. */
    OptionalLong sizeOf(String key) {
        try {
            return OptionalLong.of(client.headObject(request -> request.bucket(bucket).key(key)).contentLength());
        } catch (S3Exception failure) {
            if (failure.statusCode() == NOT_FOUND) {
                return OptionalLong.empty();
            }
            throw failure;
        }
    }

    /** The object's bytes in the range, fewer when the range runs past its end. */
    byte[] read(String key, long offset, int length) {
        String range = "bytes=%d-%d".formatted(offset, offset + length - 1);
        return client.getObjectAsBytes(request -> request.bucket(bucket).key(key).range(range)).asByteArray();
    }

    /** Every key that starts with the prefix. */
    List<String> keysUnder(String prefix) {
        return client.listObjectsV2Paginator(request -> request.bucket(bucket).prefix(prefix))
                .contents().stream()
                .map(S3Object::key)
                .toList();
    }

    void delete(String key) {
        client.deleteObject(request -> request.bucket(bucket).key(key));
    }

    @Override
    public void close() {
        client.close();
        uploadSigner.close();
        playbackSigner.close();
    }

    /**
     * The signing time has whole seconds, so the URL stops working at the expiration cut to the second, a little
     * before the presigner's own.
     */
    private static SignedUrl signed(PresignedRequest presigned) {
        return new SignedUrl(URI.create(presigned.url().toString()),
                presigned.expiration().truncatedTo(ChronoUnit.SECONDS));
    }

    /** A presigned request: whoever holds the URL may make it, until it expires. */
    record SignedUrl(URI url, Instant expiresAt) {
    }
}
