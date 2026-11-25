package com.merchanthub.product.catalog.storage;

import java.time.Duration;
import java.time.Instant;

/**
 * 商品图片存储（G-上：S3 替换 MinIO）。
 * <p>
 * 图片不经过我们的服务：服务只签发一个"限时、限 key、限类型"的上传 URL，客户端拿着它直接 PUT 到 S3。
 * 这是行业通行做法 —— 大文件不占服务的带宽和线程，服务也不用持有能写整个桶的长期凭证给客户端。
 */
public interface ImageStorage {

    /** 为 objectKey 签一个 PUT 上传 URL；客户端上传时 Content-Type 必须与签名时一致，否则 S3 拒绝 */
    PresignedUpload presignUpload(String objectKey, String contentType, Duration ttl);

    /** 对象是否真的已上传（确认绑定前校验，防止把一个不存在的 key 写进商品） */
    boolean exists(String objectKey);

    /** 图片对外访问地址（桶里 products/ 前缀公开只读） */
    String publicUrl(String objectKey);

    record PresignedUpload(String uploadUrl, Instant expiresAt) {
    }
}
