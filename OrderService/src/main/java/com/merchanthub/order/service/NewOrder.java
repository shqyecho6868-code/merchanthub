package com.merchanthub.order.service;

import java.time.LocalDateTime;

/**
 * 一笔待落库的新订单（校验、计价、预占都已完成）。
 * 订单号在预占前就生成了，是预占和"订单已创建"事件共用的业务键。
 */
public record NewOrder(long orderNo, long tenantId, long buyerId, OrderPricing.Result pricing,
                       LocalDateTime createdAt, LocalDateTime expireAt) {
}
