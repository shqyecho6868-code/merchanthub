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
 * S3 实现。签名用的是 ECS task role 的临时凭证（SDK 默认凭证链自动从容器元数据端点取），
 * 所以 URL 的有效期还受临时凭证剩余时间限制 —— 这里只签 10 分钟，远小于凭证的小时级有效期。
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
        // 预签名是纯本地计算（HMAC），不发网络请求
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
            // HeadObject 没有响应体，对象不存在时 SDK 有时只给 404 的 S3Exception 而不是 NoSuchKeyException
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
