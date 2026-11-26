package com.merchanthub.user.approval.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** product -> user-tenant: start listing approval (internal endpoint). The merchant ID isn't here: it comes from the identity headers, so callers can't pass someone else's */
public record StartListingRequest(@NotNull @Positive Long productId, @NotNull @Positive Integer round,
                                  @NotBlank String productName) {
}
