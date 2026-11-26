package com.merchanthub.product.inventory.mq;

/**
 * The "order paid / closed" event published by order (Topic ORDER_EVENT, Tag ORDER_PAID / ORDER_CLOSED).
 * The contract is the JSON fields; we define our own class with the same shape instead of sharing a jar (same as payment ↔ order).
 */
public record OrderSettledEvent(long eventId, long orderNo, long tenantId, String event) {

    public static final String TOPIC = "ORDER_EVENT";
    public static final String TAG_ORDER_PAID = "ORDER_PAID";
    public static final String TAG_ORDER_CLOSED = "ORDER_CLOSED";
}
