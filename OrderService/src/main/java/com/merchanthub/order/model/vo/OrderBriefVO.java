package com.merchanthub.order.model.vo;

/** Order summary returned by the internal API (used by payment to create payments) */
public record OrderBriefVO(long orderNo, long tenantId, long buyerId, int status, long payAmount) {
}
