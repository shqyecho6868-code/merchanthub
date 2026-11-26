package com.merchanthub.payment.client.dto;

/**
 * @param orderStatus current order status; null if the order doesn't exist
 * @param processed   whether order has already consumed this payment's success event
 */
public record PayCheckResult(long payNo, long orderNo, Integer orderStatus, boolean processed) {
}
