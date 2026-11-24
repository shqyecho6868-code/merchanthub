package com.merchanthub.payment.client.dto;

/** 对账时问 order：这张支付单的"支付成功"事件处理过没有（与 order 的同名 record 字段一致） */
public record PayCheckItem(long payNo, long orderNo) {
}
