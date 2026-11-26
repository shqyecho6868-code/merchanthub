package com.merchanthub.user.approval.model.dto;

import jakarta.validation.constraints.Size;

/** Review comment; required when rejecting (validated in the service: optional when approving) */
public record DecisionRequest(@Size(max = 200) String comment) {
}
