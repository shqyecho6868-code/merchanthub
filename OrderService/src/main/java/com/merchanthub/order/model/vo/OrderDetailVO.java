package com.merchanthub.order.model.vo;

import java.time.LocalDateTime;

/** 订单查询结果（C1）。订单号用字符串：雪花 ID 超过 JS 的安全整数范围，前端按数字解析会丢精度 */
public record OrderDetailVO(String orderNo, long tenantId, long buyerId, int status, long payAmount,
                            LocalDateTime createdAt) {
}
