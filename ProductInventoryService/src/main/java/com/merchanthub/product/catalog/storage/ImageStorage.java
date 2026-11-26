package com.merchanthub.product.catalog.storage;

import java.time.Duration;
import java.time.Instant;

/**
 * Product image storage (S3 replaces MinIO).
 * <p>
 * Images never pass through our service: the service only issues a "time-limited, key-scoped, type-scoped" upload URL, and the client PUTs directly to S3 with it.
 * This is the industry-standard approach — large files don't consume service bandwidth or threads, and the service never hands clients long-lived credentials that can write the whole bucket.
 */
public interface ImageStorage {

    /** Sign a PUT upload URL for objectKey; the client's Content-Type on upload must match the one signed, or S3 rejects it */
    PresignedUpload presignUpload(String objectKey, String contentType, Duration ttl);

    /** Whether the object was really uploaded (checked before confirming the binding, so a nonexistent key never gets written into a product) */
    boolean exists(String objectKey);

    /** Public URL of the image (the products/ prefix in the bucket is public read-only) */
    String publicUrl(String objectKey);

    record PresignedUpload(String uploadUrl, Instant expiresAt) {
    }
}
