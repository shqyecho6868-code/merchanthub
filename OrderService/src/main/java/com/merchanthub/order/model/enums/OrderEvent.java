package com.merchanthub.order.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Order state machine events: each event maps to exactly one "from -> to" edge (data-model section 4).
 * The old status of a transition is determined by the event, not by "reading the order's current status" -- otherwise the CAS condition would be meaningless.
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
