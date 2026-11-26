package com.merchanthub.realtime.push;

import java.util.Map;

/**
 * A message pushed to the client (serialized as-is into a WebSocket text frame).
 *
 * @param msgId       message ID (derived from the order event ID, stable across redeliveries). May be delivered more than once (redelivery / multiple nodes); clients dedupe by it
 * @param receiverKey receiver: merchant:{tenantId} (all online staff of the merchant) / buyer:{userId}
 * @param type        NEW_ORDER new-order alert / ORDER_STATUS order status change
 * @param data        business fields. The order number is a string: snowflake IDs exceed JS's safe integer range, so parsing them as numbers on the frontend loses precision
 */
public record PushMessage(String msgId, String receiverKey, long tenantId, String type, Map<String, Object> data, long ts) {

    public static String merchant(long tenantId) {
        return "merchant:" + tenantId;
    }

    public static String buyer(long userId) {
        return "buyer:" + userId;
    }
}
