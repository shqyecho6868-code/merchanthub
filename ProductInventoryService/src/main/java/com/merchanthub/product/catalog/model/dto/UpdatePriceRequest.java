package com.merchanthub.product.catalog.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Price change request; amount in cents */
public record UpdatePriceRequest(@NotNull @Positive Long price) {
}
