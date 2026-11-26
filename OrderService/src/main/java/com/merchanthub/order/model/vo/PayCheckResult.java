package com.merchanthub.order.model.vo;

/**
 * @param orderStatus current order status; null if the order doesn't exist
 * @param processed   whether order has already consumed this payment's "payment succeeded" event (consumed_event has a record with eventId = payNo).
 *                    processed = order marked paid, or recorded in pay_anomaly; not processed = event lost, payment needs to re-send it
 */
public record PayCheckResult(long payNo, long orderNo, Integer orderStatus, boolean processed) {
}
