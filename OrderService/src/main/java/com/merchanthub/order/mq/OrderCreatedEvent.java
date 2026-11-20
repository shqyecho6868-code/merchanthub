package com.merchanthub.order.mq;

import com.merchanthub.order.service.NewOrder;

import java.time.LocalDateTime;

/**
 * "订单已创建"事件。只放下游普遍需要的关键字段，不放整张订单（B2 笔记 Q3）：
 * 下游要明细就按 orderNo 来查，订单表结构变了也不会连累所有消费者。
 *
 * @param eventId 事件唯一 ID（雪花），重发多少次都不变，消费端按它去重
 */
public record OrderCreatedEvent(long eventId, long orderNo, long tenantId, long buyerId,
                                long payAmount, LocalDateTime createdAt) {

    public static OrderCreatedEvent of(long eventId, NewOrder order) {
        return new OrderCreatedEvent(eventId, order.orderNo(), order.tenantId(), order.buyerId(),
                order.pricing().payAmount(), order.createdAt());
    }
}
