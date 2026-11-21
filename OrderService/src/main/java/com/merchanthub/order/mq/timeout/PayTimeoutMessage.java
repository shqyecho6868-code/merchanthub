package com.merchanthub.order.mq.timeout;

/** 超时关单定时消息（Topic ORDER_TIMEOUT）。下单时和订单同事务写进 outbox，投递时间 = 订单 expire_at */
public record PayTimeoutMessage(long eventId, long orderNo, long tenantId) {
}
