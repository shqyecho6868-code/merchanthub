package com.merchanthub.payment.model.dto;

import jakarta.validation.constraints.NotNull;

public record CreatePaymentRequest(@NotNull Long orderNo) {
}
