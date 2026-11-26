package com.merchanthub.user.approval.model.vo;

/** Onboarding application (without the password hash) */
public record ApplicationVO(long id, long applicantUserId, String shopName, String adminUsername, int status,
                            String rejectReason, int submitCount, String processInstanceId, Long tenantId) {
}
