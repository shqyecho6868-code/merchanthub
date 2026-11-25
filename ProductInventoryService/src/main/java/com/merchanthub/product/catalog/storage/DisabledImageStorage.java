package com.merchanthub.product.catalog.storage;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;

import java.time.Duration;

/**
 * 没配 S3 时（本地开发、测试）的占位实现：调用图片接口直接报"未开启"，其他功能不受影响。
 * 本地 docker-compose 里没有 MinIO，不为了本地演示再引入一个对象存储。
 */
public class DisabledImageStorage implements ImageStorage {

    private static BusinessException disabled() {
        return new BusinessException(ErrorCode.OPERATION_ERROR, "对象存储未开启（merchanthub.storage.s3.enabled=false）");
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
