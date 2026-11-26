package com.merchanthub.user.approval.model.vo;

/**
 * Listing approval record lookup result (used by product's compensation scan).
 *
 * @param state NOT_FOUND (never started) / RUNNING / APPROVED / REJECTED
 */
public record ListingResultVO(String state, String processInstanceId, String rejectReason) {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String RUNNING = "RUNNING";
}
