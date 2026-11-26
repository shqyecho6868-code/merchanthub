package com.merchanthub.product.catalog.model.vo;

import java.time.Instant;

/**
 * @param objectKey pass back unchanged to the confirm API after uploading
 * @param uploadUrl the client uploads with HTTP PUT; the header must carry the same Content-Type
 * @param imageUrl  URL of the product's main image once bound
 */
public record ImageUploadVO(String objectKey, String uploadUrl, Instant expiresAt, String imageUrl) {
}
