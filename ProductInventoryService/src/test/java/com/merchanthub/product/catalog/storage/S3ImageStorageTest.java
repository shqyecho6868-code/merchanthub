package com.merchanthub.product.catalog.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Presigning is a local HMAC computation, so it can be tested with fake credentials — no network or real AWS account needed.
 */
class S3ImageStorageTest {

    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "secret")))
            .build();
    private final S3Client s3 = mock(S3Client.class);
    private final S3ImageStorage storage = new S3ImageStorage(presigner, s3, "mh-images", "us-east-1");

    @AfterEach
    void close() {
        presigner.close();
    }

    @Test
    void presignedUrlIsScopedToKeyAndExpires() {
        Instant before = Instant.now();
        ImageStorage.PresignedUpload up = storage.presignUpload("products/7/100/a.png", "image/png", Duration.ofMinutes(10));

        assertThat(up.uploadUrl())
                .startsWith("https://mh-images.s3.amazonaws.com/products/7/100/a.png?")
                .contains("X-Amz-Expires=600")
                .contains("X-Amz-Signature=")
                // Content-Type is part of the signature: if the client uploads with a different type, S3 returns 403
                .contains("content-type");
        assertThat(up.expiresAt()).isBetween(before.plusSeconds(590), before.plusSeconds(610));
    }

    @Test
    void publicUrlUsesRegionalEndpoint() {
        assertThat(storage.publicUrl("products/7/100/a.png"))
                .isEqualTo("https://mh-images.s3.us-east-1.amazonaws.com/products/7/100/a.png");
    }

    @Test
    void existsMapsNotFoundToFalse() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build())
                .thenThrow(NoSuchKeyException.builder().build())
                .thenThrow(S3Exception.builder().statusCode(404).build());
        assertThat(storage.exists("k")).isTrue();
        assertThat(storage.exists("k")).isFalse();
        assertThat(storage.exists("k")).isFalse();
    }

    @Test
    void existsRethrowsOtherErrors() {
        // A 403 (task role lacks permission) must not be swallowed as "not uploaded"; the caller needs to see the real cause
        when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(S3Exception.builder().statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").build()).build());
        assertThatThrownBy(() -> storage.exists("k")).isInstanceOf(S3Exception.class);
    }
}
