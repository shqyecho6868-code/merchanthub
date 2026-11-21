package com.merchanthub.payment.mq;

import java.time.LocalDateTime;

/**
 * "支付成功"事件。eventId 直接用 payNo：一张支付单最多成功一次，所以 payNo 天然标识这个事件。
 * 好处是确定性 —— 就算极端情况下同一张支付单的事件被发出两次（比如事务回查重复提交），eventId 也相同，
 * order 消费端的去重表能挡住；用随机雪花 ID 就做不到。
 */
public record PaySuccessEvent(long eventId, long payNo, long orderNo, long tenantId, long buyerId,
                              long amount, String channelTradeNo, LocalDateTime paidAt) {
}
