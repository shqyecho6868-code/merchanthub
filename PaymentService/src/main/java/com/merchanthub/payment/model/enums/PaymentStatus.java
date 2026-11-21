package com.merchanthub.payment.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 支付单状态（data-model 3.6）。只允许 待支付 → 成功 / 失败 / 已关闭，三个都是终态 */
@Getter
@AllArgsConstructor
public enum PaymentStatus {
    PENDING(0),
    SUCCESS(1),
    FAILED(2),
    CLOSED(3);

    private final int code;
}
