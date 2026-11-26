package com.merchanthub.payment.mq;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.payment.mapper.PaymentMapper;
import com.merchanthub.payment.model.entity.Payment;
import com.merchanthub.payment.model.enums.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;

/**
 * "Payment succeeded" transactional message: half message → local transaction (payment CAS) → COMMIT / ROLLBACK.
 * Key point: <b>COMMIT only if the CAS won</b>. A duplicate callback's CAS affects 0 rows → ROLLBACK; its half message is deleted and downstream never sees it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentTxListener implements TransactionListener {

    private final PaymentMapper paymentMapper;
    private final PaymentEventCodec codec;
    private final Clock clock;

    @Value("${merchanthub.payment.tx.rollback-min-age-seconds:30}")
    private long rollbackMinAgeSeconds;

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        CasTx tx = (CasTx) arg;
        if (tx.run()) {
            return LocalTransactionState.COMMIT_MESSAGE;
        }
        // CAS lost (duplicate callback) or the local transaction threw: delete the half message, no event produced
        return LocalTransactionState.ROLLBACK_MESSAGE;
    }

    /**
     * Check-back: when the COMMIT / ROLLBACK didn't reach the Broker (both are sent oneway, delivery not guaranteed), the Broker asks "should this event be sent or not".
     * Looks only at the payment in the shared database: succeeded and the winning attempt ID is this message's → send; still pending and the message is recent → ask again later; anything else → don't send.
     * <p>
     * Why not just check "payment succeeded": with concurrent duplicate callbacks, the half messages that lost the CAS also get checked back, and by then the payment has already succeeded;
     * checking status alone would COMMIT all of them — 10 concurrent callbacks produced 11 events (observed in an integration test).
     */
    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        PaySuccessEvent event;
        try {
            event = codec.fromMessage(msg);
        } catch (IllegalArgumentException e) {
            log.error("Payment tx check-back: message body unparsable, rolling back msgId={}", msg.getMsgId(), e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        Payment p;
        try {
            // Check-back runs on a RocketMQ thread with no tenant context: query by the merchant in the event
            p = TenantContext.callAs(TenantInfo.systemOf(event.tenantId()), () -> paymentMapper.selectByPayNo(event.payNo()));
        } catch (RuntimeException e) {
            log.warn("Payment tx check-back: DB lookup failed, will check again later payNo={}", event.payNo(), e);
            return LocalTransactionState.UNKNOW;
        }
        LocalTransactionState state;
        String attempt = msg.getUserProperty(PaymentEventTopics.PROP_ATTEMPT);
        if (p != null && p.getStatus() == PaymentStatus.SUCCESS.getCode()
                && Objects.equals(p.getSuccessAttempt(), attempt)) {
            state = LocalTransactionState.COMMIT_MESSAGE;
        } else if (p != null && p.getStatus() == PaymentStatus.PENDING.getCode()
                && clock.millis() - msg.getBornTimestamp() < rollbackMinAgeSeconds * 1000) {
            state = LocalTransactionState.UNKNOW;
        } else {
            state = LocalTransactionState.ROLLBACK_MESSAGE;
        }
        log.info("Payment tx check-back payNo={} orderNo={} tenantId={} → {}", event.payNo(), event.orderNo(), event.tenantId(), state);
        return state;
    }
}
