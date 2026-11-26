package com.merchanthub.product.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;

/** Request an upload URL: declares the image type to upload (image/jpeg, image/png, image/webp) */
public record ImageUploadRequest(@NotBlank String contentType) {
}
