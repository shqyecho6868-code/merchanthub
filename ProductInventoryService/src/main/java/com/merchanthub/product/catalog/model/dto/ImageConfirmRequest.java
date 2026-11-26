package com.merchanthub.product.catalog.model.dto;

import jakarta.validation.constraints.NotBlank;

/** After the upload completes, bind objectKey as the product's main image */
public record ImageConfirmRequest(@NotBlank String objectKey) {
}
