package com.merchanthub.product.catalog.mq;

/**
 * 上架审批结果事件（user-tenant 发，product 收）。各服务各持一份消息契约，同 OrderSettledEvent 的做法。
 *
 * @param result APPROVED / REJECTED
 */
public record ListingResultEvent(long eventId, long tenantId, long productId, int round, String result,
                                 String rejectReason, String processInstanceId) {

    public static final String TOPIC = "PRODUCT_LISTING_RESULT";
    public static final String TAG = "LISTING_RESULT";
    public static final String APPROVED = "APPROVED";
}
