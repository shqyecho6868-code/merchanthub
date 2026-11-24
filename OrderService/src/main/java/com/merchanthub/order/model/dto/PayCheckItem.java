package com.merchanthub.order.model.dto;

/** payment 对账时问 order：这张支付单的"支付成功"事件，订单处理过没有 */
public record PayCheckItem(long payNo, long orderNo) {
}
