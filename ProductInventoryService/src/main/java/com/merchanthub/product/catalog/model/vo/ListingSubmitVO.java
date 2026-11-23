package com.merchanthub.product.catalog.model.vo;

/** @param processInstanceId 为空表示审批还在发起中（user-tenant 暂时不可用），系统会自动重试，商家不需要重新提交 */
public record ListingSubmitVO(long productId, int auditRound, int status, String processInstanceId) {
}
