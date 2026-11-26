package com.merchanthub.product.catalog.storage;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

import java.time.Duration;

/**
 * S3 implementation. Signing uses the ECS task role's temporary credentials (the SDK default credential chain fetches them from the container metadata endpoint),
 * so URL validity is also bounded by the credentials' remaining lifetime — we sign for only 10 minutes, far below the credentials' hour-scale validity.
 */
public class S3ImageStorage implements ImageStorage {

    private final S3Presigner presigner;
    private final S3Client s3;
    private final String bucket;
    private final String region;

    public S3ImageStorage(S3Presigner presigner, S3Client s3, String bucket, String region) {
        this.presigner = presigner;
        this.s3 = s3;
        this.bucket = bucket;
        this.region = region;
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, Duration ttl) {
        // Presigning is purely local computation (HMAC), no network request
        PresignedPutObjectRequest req = presigner.presignPutObject(b -> b
                .signatureDuration(ttl)
                .putObjectRequest(PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(contentType).build()));
        return new PresignedUpload(req.url().toString(), req.expiration());
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // HeadObject has no response body; for a missing object the SDK sometimes only gives a 404 S3Exception instead of NoSuchKeyException
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public String publicUrl(String objectKey) {
        return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + objectKey;
    }
}
