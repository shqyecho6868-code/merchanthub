package com.merchanthub.product.catalog.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 改价请求，金额单位：分 */
public record UpdatePriceRequest(@NotNull @Positive Long price) {
}
