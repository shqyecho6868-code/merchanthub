package com.merchanthub.payment.mq;

import java.time.LocalDateTime;

/**
 * "Payment succeeded" event. eventId is simply payNo: a payment can succeed at most once, so payNo naturally identifies this event.
 * The benefit is determinism — even if, in an edge case, the same payment's event is sent twice (e.g. a duplicate commit from tx check-back), the eventId is the same
 * and order's consumer dedup table can stop it; a random snowflake ID couldn't do that.
 */
public record PaySuccessEvent(long eventId, long payNo, long orderNo, long tenantId, long buyerId,
                              long amount, String channelTradeNo, LocalDateTime paidAt) {
}
