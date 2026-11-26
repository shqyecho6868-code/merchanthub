package com.merchanthub.payment.client.dto;

import lombok.Data;

/** Order summary returned by order's internal API (only the fields needed to create a payment) */
@Data
public class OrderBrief {
    public static final int PENDING_PAYMENT = 10;

    private Long orderNo;
    private Long tenantId;
    private Long buyerId;
    private Integer status;
    private Long payAmount;
}
