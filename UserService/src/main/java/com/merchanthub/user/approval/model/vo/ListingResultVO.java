package com.merchanthub.user.approval.model.vo;

/**
 * 上架审批单查询结果（product 补偿扫描用）。
 *
 * @param state NOT_FOUND（没发起过）/ RUNNING / APPROVED / REJECTED
 */
public record ListingResultVO(String state, String processInstanceId, String rejectReason) {

    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String RUNNING = "RUNNING";
}
