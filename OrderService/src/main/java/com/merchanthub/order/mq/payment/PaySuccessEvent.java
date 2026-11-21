package com.merchanthub.order.mq.payment;

import java.time.LocalDateTime;

/**
 * payment 发出的"支付成功"事件（契约是 JSON 字段，order 自己定义一份同结构的类，不共享 jar，避免服务间编译期耦合）。
 * eventId = payNo（见 payment 侧注释）。
 */
public record PaySuccessEvent(long eventId, long payNo, long orderNo, long tenantId, long buyerId,
                              long amount, String channelTradeNo, LocalDateTime paidAt) {

    public static final String TOPIC = "PAYMENT_EVENT";
    public static final String TAG = "PAY_SUCCESS";
}
