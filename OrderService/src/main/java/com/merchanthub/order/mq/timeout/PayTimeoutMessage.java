package com.merchanthub.order.mq.timeout;

/** Timeout-close scheduled message (Topic ORDER_TIMEOUT). Written to the outbox in the same transaction as the order at placement; delivery time = order expire_at */
public record PayTimeoutMessage(long eventId, long orderNo, long tenantId) {
}
