package com.merchanthub.order.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Order status (spaced codes so new statuses can be inserted in between later; see data-model section 4) */
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
