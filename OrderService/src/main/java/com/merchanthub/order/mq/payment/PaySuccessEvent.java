package com.merchanthub.order.mq.payment;

import java.time.LocalDateTime;

/**
 * "Payment succeeded" event sent by payment (the contract is the JSON fields; order defines its own class with the same shape instead of sharing a jar, avoiding compile-time coupling between services).
 * eventId = payNo (see the comments on the payment side).
 */
public record PaySuccessEvent(long eventId, long payNo, long orderNo, long tenantId, long buyerId,
                              long amount, String channelTradeNo, LocalDateTime paidAt) {

    public static final String TOPIC = "PAYMENT_EVENT";
    public static final String TAG = "PAY_SUCCESS";
}
