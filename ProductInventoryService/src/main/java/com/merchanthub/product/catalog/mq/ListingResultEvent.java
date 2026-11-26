package com.merchanthub.product.catalog.mq;

/**
 * Listing approval result event (sent by user-tenant, received by product). Each service keeps its own copy of the message contract, same as OrderSettledEvent.
 *
 * @param result APPROVED / REJECTED
 */
public record ListingResultEvent(long eventId, long tenantId, long productId, int round, String result,
                                 String rejectReason, String processInstanceId) {

    public static final String TOPIC = "PRODUCT_LISTING_RESULT";
    public static final String TAG = "LISTING_RESULT";
    public static final String APPROVED = "APPROVED";
}
