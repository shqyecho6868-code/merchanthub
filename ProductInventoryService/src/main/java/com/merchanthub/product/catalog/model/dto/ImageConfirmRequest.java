package com.merchanthub.product.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;

/** 上传完成后，把 objectKey 绑定为商品主图 */
public record ImageConfirmRequest(@NotBlank String objectKey) {
}
