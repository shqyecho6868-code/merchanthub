package com.merchanthub.product.catalog.model.vo;

import java.time.Instant;

/**
 * @param objectKey 上传完成后调确认接口时原样传回
 * @param uploadUrl 客户端用 HTTP PUT 上传，Header 必须带同样的 Content-Type
 * @param imageUrl  绑定后商品主图的访问地址
 */
public record ImageUploadVO(String objectKey, String uploadUrl, Instant expiresAt, String imageUrl) {
}
