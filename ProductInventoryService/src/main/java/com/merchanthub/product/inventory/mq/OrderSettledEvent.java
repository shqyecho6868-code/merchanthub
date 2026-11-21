package com.merchanthub.product.inventory.mq;

/**
 * order 发出的"订单已支付 / 已关闭"事件（Topic ORDER_EVENT，Tag ORDER_PAID / ORDER_CLOSED）。
 * 契约是 JSON 字段，这里自己定义一份同结构的类，不共享 jar（同 payment ↔ order 的做法）。
 */
public record OrderSettledEvent(long eventId, long orderNo, long tenantId, String event) {

    public static final String TOPIC = "ORDER_EVENT";
    public static final String TAG_ORDER_PAID = "ORDER_PAID";
    public static final String TAG_ORDER_CLOSED = "ORDER_CLOSED";
}
