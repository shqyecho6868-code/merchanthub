package com.merchanthub.product.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;

/** 申请上传 URL：声明要传的图片类型（image/jpeg、image/png、image/webp） */
public record ImageUploadRequest(@NotBlank String contentType) {
}
