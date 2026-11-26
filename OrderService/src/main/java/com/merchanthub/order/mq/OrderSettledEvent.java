package com.merchanthub.order.mq;

/**
 * "Order paid / closed" event (Tag ORDER_PAID / ORDER_CLOSED): written to the outbox in the same transaction only when the order CAS wins.
 * Inventory confirms the deduction or releases the reservation based on it. Carries only the fields needed to locate the reservation; downstreams query by orderNo for anything else.
 *
 * @param event   the triggering order event (PAY_SUCCESS / PAY_TIMEOUT / BUYER_CANCEL), logged on release to help investigate "why stock was returned"
 * @param buyerId added later: realtime uses it to push "order status changed" to the buyer. Boxed type: old messages sent before it was added lack this field and deserialize to null
 */
public record OrderSettledEvent(long eventId, long orderNo, long tenantId, String event, Long buyerId) {
}
