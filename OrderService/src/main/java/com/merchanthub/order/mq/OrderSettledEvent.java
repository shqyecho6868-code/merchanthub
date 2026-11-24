package com.merchanthub.order.mq;

/**
 * "订单已支付 / 已关闭"事件（Tag ORDER_PAID / ORDER_CLOSED）：订单 CAS 赢了才会在同一事务里写进 outbox。
 * 库存据此确认扣减或释放预占。只带定位预占记录需要的字段，下游要别的自己按 orderNo 查。
 *
 * @param event   触发的订单事件（PAY_SUCCESS / PAY_TIMEOUT / BUYER_CANCEL），释放时写进日志，方便排查"为什么还库存"
 * @param buyerId E1 新增：realtime 据此给买家推"订单状态变化"。用包装类型：E1 之前发出的老消息没有这个字段，反序列化成 null
 */
public record OrderSettledEvent(long eventId, long orderNo, long tenantId, String event, Long buyerId) {
}
