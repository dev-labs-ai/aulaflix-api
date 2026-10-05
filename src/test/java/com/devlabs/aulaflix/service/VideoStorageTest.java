package com.devlabs.aulaflix.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Everything else the storage does shows through the HTTP contract against AIStor. Releasing its connections when the
 * application stops does not, and that release is the whole contract here.
 */
class VideoStorageTest {

    @Test
    void closesItsClientAndBothSignersWhenClosed() {
        S3Client client = mock(S3Client.class);
        S3Presigner uploadSigner = mock(S3Presigner.class);
        S3Presigner playbackSigner = mock(S3Presigner.class);

        new VideoStorage(client, uploadSigner, playbackSigner, "videos", Duration.ofHours(4)).close();

        verify(client).close();
        verify(uploadSigner).close();
        verify(playbackSigner).close();
    }
}
