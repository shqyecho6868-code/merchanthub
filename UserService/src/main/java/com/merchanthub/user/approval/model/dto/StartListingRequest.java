package com.merchanthub.user.approval.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** product → user-tenant 发起上架审批（内部接口）。商家 ID 不在这里：从请求头身份取，调用方传不了别人的 */
public record StartListingRequest(@NotNull @Positive Long productId, @NotNull @Positive Integer round,
                                  @NotBlank String productName) {
}
