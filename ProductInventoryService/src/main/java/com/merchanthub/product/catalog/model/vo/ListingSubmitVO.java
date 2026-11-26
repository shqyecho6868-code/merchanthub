package com.merchanthub.product.catalog.model.vo;

/** @param processInstanceId null means the approval is still being started (user-tenant temporarily unavailable); the system retries automatically and the merchant doesn't need to resubmit */
public record ListingSubmitVO(long productId, int auditRound, int status, String processInstanceId) {
}
