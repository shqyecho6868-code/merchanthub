package com.merchanthub.order.mq.tx;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.mq.OrderCreatedEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * The two transactional-message callbacks:
 * <ol>
 *   <li>executeLocalTransaction: right after the half message is sent, the client calls it on the <b>current thread</b> to run the local transaction (write the order)</li>
 *   <li>checkLocalTransaction: when the broker hasn't received COMMIT/ROLLBACK for a while (process died, network dropped, UNKNOW returned),
 *       it actively asks "did this transaction succeed?". Runs in the client's check-back thread pool, unrelated to the original sending thread or even process</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "merchanthub.order.event-mode", havingValue = "TX_MESSAGE")
public class OrderTxListener implements TransactionListener {

    private final OrdersMapper ordersMapper;
    private final OrderEventCodec codec;
    private final Clock clock;

    /**
     * If the order isn't found, the message must be at least this old before we decide ROLLBACK; otherwise return UNKNOW so the broker asks again later.
     * Must exceed the local transaction timeout (5s). This is a second line of defense beyond the check-immunity time: in B2 the check-back arrived earlier than that (see pitfalls),
     * so we can't rely only on the broker-side window. bornTimestamp is the producer's own clock, unaffected by the broker's clock.
     */
    @Value("${merchanthub.order.tx.rollback-min-age-seconds:30}")
    private long rollbackMinAgeSeconds;

    /** For the 🧑 kill experiment: pause N ms after the local commit before returning COMMIT, leaving a window to kill the process. Must be 0 in production */
    @Value("${merchanthub.order.tx.debug.pause-after-local-commit-ms:0}")
    private long pauseAfterLocalCommitMs;

    /** For integration tests: return UNKNOW after the local commit, equivalent to "COMMIT never reached the broker", to trigger a check-back. Must be false in production */
    @Value("${merchanthub.order.tx.debug.report-unknown:false}")
    private boolean reportUnknown;

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        LocalTx tx = (LocalTx) arg;
        if (!tx.run()) {
            // Local transaction rolled back -> delete the half message; consumers never see it
            log.warn("本地事务失败，回滚半消息 keys={}", msg.getKeys(), tx.failure());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        // ---- At this point: the order is committed to the DB, but the broker doesn't know yet ----
        if (pauseAfterLocalCommitMs > 0) {
            log.warn("【kill 实验】本地事务已提交 keys={}，{} ms 后才提交消息，现在 kill 进程", msg.getKeys(), pauseAfterLocalCommitMs);
            try {
                Thread.sleep(pauseAfterLocalCommitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (reportUnknown) {
            log.warn("【测试】本地事务已提交但返回 UNKNOW，等待 Broker 回查 keys={}", msg.getKeys());
            return LocalTransactionState.UNKNOW;
        }
        return LocalTransactionState.COMMIT_MESSAGE;
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        String checkTimes = msg.getUserProperty(MessageConst.PROPERTY_TRANSACTION_CHECK_TIMES);
        OrderCreatedEvent event;
        try {
            event = codec.fromMessage(msg);
        } catch (IllegalArgumentException e) {
            // Corrupt message body: can neither decide nor consume; roll back and discard, leave a log for manual review
            log.error("事务回查：消息体无法解析，回滚 msgId={} keys={}", msg.getMsgId(), msg.getKeys(), e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        boolean exists;
        try {
            // Decide the local transaction result by order number. This works only because the local transaction has always finished by check-back time
            // (local tx timeout 5s < check-immunity time 30s, B2 notes Q2)
            // The check-back thread is a RocketMQ thread with no tenant context: query by the merchant in the event (which also verifies the order really belongs to it)
            exists = TenantContext.callAs(TenantInfo.systemOf(event.tenantId()), () ->
                    ordersMapper.exists(new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, event.orderNo())));
        } catch (RuntimeException e) {
            // DB query failure != order doesn't exist. Returning ROLLBACK here could delete the message of an order that may already exist;
            // return UNKNOW so the broker asks again later
            log.warn("事务回查：查库失败，稍后重查 orderNo={} tenantId={} checkTimes={}",
                    event.orderNo(), event.tenantId(), checkTimes, e);
            return LocalTransactionState.UNKNOW;
        }
        long ageMs = clock.millis() - msg.getBornTimestamp();
        if (!exists && ageMs < rollbackMinAgeSeconds * 1000) {
            // The local transaction may not be done yet: rolling back now would mean "order created but message lost" if it commits later
            log.info("事务回查 orderNo={} tenantId={} checkTimes={} 订单暂不存在、消息仅 {} ms，稍后重查 → UNKNOW",
                    event.orderNo(), event.tenantId(), checkTimes, ageMs);
            return LocalTransactionState.UNKNOW;
        }
        LocalTransactionState state = exists ? LocalTransactionState.COMMIT_MESSAGE : LocalTransactionState.ROLLBACK_MESSAGE;
        log.info("事务回查 orderNo={} tenantId={} eventId={} checkTimes={} 订单存在={} → {}",
                event.orderNo(), event.tenantId(), event.eventId(), checkTimes, exists, state);
        return state;
    }
}
