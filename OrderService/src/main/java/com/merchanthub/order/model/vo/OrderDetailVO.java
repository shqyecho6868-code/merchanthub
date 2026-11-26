package com.merchanthub.order.model.vo;

import java.time.LocalDateTime;

/** Order query result. The order number is a string: snowflake IDs exceed JS's safe integer range and lose precision if parsed as numbers on the frontend */
public record OrderDetailVO(String orderNo, long tenantId, long buyerId, int status, long payAmount,
                            LocalDateTime createdAt) {
}
