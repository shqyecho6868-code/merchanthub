package com.merchanthub.user.approval.model.vo;

/** 入驻申请（不含密码哈希） */
public record ApplicationVO(long id, long applicantUserId, String shopName, String adminUsername, int status,
                            String rejectReason, int submitCount, String processInstanceId, Long tenantId) {
}
