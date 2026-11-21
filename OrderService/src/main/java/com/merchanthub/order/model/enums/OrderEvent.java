package com.merchanthub.order.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 订单状态机的事件：每个事件只对应一条"从哪到哪"的边（data-model 第 4 节）。
 * 流转的旧状态由事件决定，而不是"读出订单当前是什么状态" —— 否则 CAS 的条件就失去意义了。
 */
@Getter
@AllArgsConstructor
public enum OrderEvent {
    PAY_SUCCESS(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID),
    BUYER_CANCEL(OrderStatus.PENDING_PAYMENT, OrderStatus.CLOSED),
    PAY_TIMEOUT(OrderStatus.PENDING_PAYMENT, OrderStatus.CLOSED),
    SHIP(OrderStatus.PAID, OrderStatus.SHIPPED),
    CONFIRM(OrderStatus.SHIPPED, OrderStatus.COMPLETED),
    AUTO_CONFIRM(OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    private final OrderStatus from;
    private final OrderStatus to;
}
