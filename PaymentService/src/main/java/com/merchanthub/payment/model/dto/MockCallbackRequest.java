package com.merchanthub.payment.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Simulated async "payment succeeded" notification from a third-party channel. Real channels also send a signature and reject on verification failure; this project doesn't integrate a real channel, so signature verification is omitted.
 *
 * @param payNo          the merchant order number we passed to the channel when placing the payment
 * @param channelTradeNo the channel's own transaction number (identical across duplicate notifications for the same payment)
 * @param amount         amount actually collected by the channel (cents); must match the payment amount
 */
public record MockCallbackRequest(@NotNull Long payNo, @NotBlank String channelTradeNo, @NotNull Long amount) {
}
