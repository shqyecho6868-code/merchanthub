package com.merchanthub.product.inventory.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 预占记录状态。CONFIRMED、RELEASED 都是终态且互斥：
 * B2/B3 用 {@code UPDATE ... WHERE status = RESERVED} 做 CAS，支付确认和超时释放同时到达时只有一个能成功。
 */
@Getter
@AllArgsConstructor
public enum ReservationStatus {
    RESERVED(10),
    CONFIRMED(20),
    RELEASED(30);

    private final int code;
}
