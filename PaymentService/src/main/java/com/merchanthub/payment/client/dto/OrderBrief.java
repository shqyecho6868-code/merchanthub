package com.merchanthub.payment.client.dto;

import lombok.Data;

/** order 内部接口返回的订单摘要（只取建支付单需要的字段） */
@Data
public class OrderBrief {
    public static final int PENDING_PAYMENT = 10;

    private Long orderNo;
    private Long tenantId;
    private Long buyerId;
    private Integer status;
    private Long payAmount;
}
