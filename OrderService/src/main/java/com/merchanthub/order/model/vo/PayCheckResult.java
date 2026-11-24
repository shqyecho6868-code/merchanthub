package com.merchanthub.order.model.vo;

/**
 * @param orderStatus 订单当前状态；订单不存在为 null
 * @param processed   order 是否已经消费过这张支付单的"支付成功"事件（consumed_event 里有 eventId = payNo 的记录）。
 *                    处理过 = 订单已改成已支付，或已记进 pay_anomaly；没处理过 = 事件丢了，payment 需要补发
 */
public record PayCheckResult(long payNo, long orderNo, Integer orderStatus, boolean processed) {
}
