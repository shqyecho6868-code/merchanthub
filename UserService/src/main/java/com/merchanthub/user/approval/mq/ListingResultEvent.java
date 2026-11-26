package com.merchanthub.user.approval.mq;

/**
 * Listing approval result event (user-tenant -> product). product has a class with the same structure (each service owns its message contract, no shared jar, same as OrderSettledEvent).
 *
 * @param round  which review submission this is on the product side: product writes back with {@code audit_round = ?}, so a late message from the previous round can't change this round
 * @param result APPROVED / REJECTED
 */
public record ListingResultEvent(long eventId, long tenantId, long productId, int round, String result,
                                 String rejectReason, String processInstanceId) {

    public static final String TOPIC = "PRODUCT_LISTING_RESULT";
    public static final String TAG = "LISTING_RESULT";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
}
