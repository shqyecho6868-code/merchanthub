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
 * "支付成功"事务消息：半消息 → 本地事务（支付单 CAS）→ COMMIT / ROLLBACK。
 * 关键点：<b>只有 CAS 赢了才 COMMIT</b>。重复回调的 CAS 影响 0 行 → ROLLBACK，它的半消息被删掉，下游永远看不到。
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
        // CAS 输了（重复回调）或本地事务异常：半消息删掉，不产生事件
        return LocalTransactionState.ROLLBACK_MESSAGE;
    }

    /**
     * 回查：COMMIT / ROLLBACK 没送到 Broker 时（二者都是 oneway 发送，不保证到达），Broker 来问"这条事件到底发不发"。
     * 只看共享数据库里的支付单：成功且赢家尝试 ID 就是这条消息的 → 发；还是待支付且消息很新 → 稍后再问；其余 → 不发。
     * <p>
     * 为什么不能只看"支付单已成功"：并发重复回调时，输掉 CAS 的那些半消息也会被回查，此时支付单已经成功了，
     * 只看状态会把它们全部 COMMIT —— 10 次并发回调发出 11 条事件（B3 集成测试实测，pitfalls P19）。
     */
    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        PaySuccessEvent event;
        try {
            event = codec.fromMessage(msg);
        } catch (IllegalArgumentException e) {
            log.error("支付事务回查：消息体无法解析，回滚 msgId={}", msg.getMsgId(), e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        Payment p;
        try {
            // 回查跑在 RocketMQ 的线程上，没有租户上下文：按事件里的商家查
            p = TenantContext.callAs(TenantInfo.systemOf(event.tenantId()), () -> paymentMapper.selectByPayNo(event.payNo()));
        } catch (RuntimeException e) {
            log.warn("支付事务回查：查库失败，稍后重查 payNo={}", event.payNo(), e);
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
        log.info("支付事务回查 payNo={} orderNo={} tenantId={} → {}", event.payNo(), event.orderNo(), event.tenantId(), state);
        return state;
    }
}
