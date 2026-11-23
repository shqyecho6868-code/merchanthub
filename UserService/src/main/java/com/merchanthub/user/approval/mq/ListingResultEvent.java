package com.merchanthub.user.approval.mq;

/**
 * 上架审批结果事件（user-tenant → product）。product 侧有同结构的类（各服务各自持有消息契约，不共享 jar，同 OrderSettledEvent）。
 *
 * @param round  product 侧第几次提交审核：product 回写时带上 {@code audit_round = ?}，上一轮的迟到消息改不了这一轮
 * @param result APPROVED / REJECTED
 */
public record ListingResultEvent(long eventId, long tenantId, long productId, int round, String result,
                                 String rejectReason, String processInstanceId) {

    public static final String TOPIC = "PRODUCT_LISTING_RESULT";
    public static final String TAG = "LISTING_RESULT";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
}
