package com.merchanthub.order.model.vo;

/** 内部接口返回的订单摘要（给 payment 建支付单用） */
public record OrderBriefVO(long orderNo, long tenantId, long buyerId, int status, long payAmount) {
}
