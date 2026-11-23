package com.merchanthub.user.approval.model.dto;

import jakarta.validation.constraints.Size;

/** 审批意见；驳回时必填（在 service 里校验：通过时可以不填） */
public record DecisionRequest(@Size(max = 200) String comment) {
}
