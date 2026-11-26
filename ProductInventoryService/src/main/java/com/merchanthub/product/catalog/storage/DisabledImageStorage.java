package com.merchanthub.product.catalog.storage;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;

import java.time.Duration;

/**
 * Placeholder used when S3 isn't configured (local dev, tests): image APIs fail with "not enabled", other features are unaffected.
 * The local docker-compose has no MinIO; we don't add an object store just for local demos.
 */
public class DisabledImageStorage implements ImageStorage {

    private static BusinessException disabled() {
        return new BusinessException(ErrorCode.OPERATION_ERROR, "Object storage is not enabled (merchanthub.storage.s3.enabled=false)");
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, Duration ttl) {
        throw disabled();
    }

    @Override
    public boolean exists(String objectKey) {
        throw disabled();
    }

    @Override
    public String publicUrl(String objectKey) {
        throw disabled();
    }
}
