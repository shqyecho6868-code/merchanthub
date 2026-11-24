package com.merchanthub.payment.client.dto;

/**
 * @param orderStatus 订单当前状态；订单不存在为 null
 * @param processed   order 是否已消费过这张支付单的支付成功事件
 */
public record PayCheckResult(long payNo, long orderNo, Integer orderStatus, boolean processed) {
}
