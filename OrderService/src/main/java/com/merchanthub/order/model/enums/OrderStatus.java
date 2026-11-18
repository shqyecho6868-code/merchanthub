package com.merchanthub.order.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 订单状态（间隔编码，以后可在中间插入新状态，见 data-model 第 4 节） */
@Getter
@AllArgsConstructor
public enum OrderStatus {
    PENDING_PAYMENT(10),
    PAID(20),
    SHIPPED(30),
    COMPLETED(40),
    CLOSED(50);

    private final int code;
}
