package com.merchanthub.order.mq;

import com.merchanthub.order.service.NewOrder;

import java.time.LocalDateTime;

/**
 * "Order created" event. Carries only the key fields downstreams commonly need, not the whole order:
 * downstreams that need items query by orderNo, so order table changes don't ripple to every consumer.
 *
 * @param eventId unique event ID (snowflake), unchanged across resends; consumers deduplicate by it
 */
public record OrderCreatedEvent(long eventId, long orderNo, long tenantId, long buyerId,
                                long payAmount, LocalDateTime createdAt) {

    public static OrderCreatedEvent of(long eventId, NewOrder order) {
        return new OrderCreatedEvent(eventId, order.orderNo(), order.tenantId(), order.buyerId(),
                order.pricing().payAmount(), order.createdAt());
    }
}
