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
 * 事务消息的两个回调：
 * <ol>
 *   <li>executeLocalTransaction：半消息发送成功后，客户端立刻在<b>当前线程</b>调用它执行本地事务（写订单）</li>
 *   <li>checkLocalTransaction：Broker 迟迟没收到 COMMIT/ROLLBACK（进程挂了、网络断了、返回了 UNKNOW）时，
 *       主动来问"这个事务到底成没成"。运行在客户端的回查线程池里，和当初发消息的线程、甚至进程都无关</li>
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
     * 查不到订单时，消息至少要"出生"这么久才判 ROLLBACK，否则返回 UNKNOW 让 Broker 稍后再问。
     * 必须大于本地事务超时（5 秒）。这是免回查时间之外的第二道防线：B2 实测回查比免回查时间来得早（见 pitfalls），
     * 不能只靠 Broker 那边的时间窗口。bornTimestamp 是生产者自己的时钟，不受 Broker 时钟影响。
     */
    @Value("${merchanthub.order.tx.rollback-min-age-seconds:30}")
    private long rollbackMinAgeSeconds;

    /** 🧑 kill 实验用：本地事务提交后停顿多少毫秒再返回 COMMIT，给你留出 kill 进程的窗口。生产必须为 0 */
    @Value("${merchanthub.order.tx.debug.pause-after-local-commit-ms:0}")
    private long pauseAfterLocalCommitMs;

    /** 集成测试用：本地事务提交后返回 UNKNOW，效果等同于"COMMIT 没送到 Broker"，用来触发回查。生产必须为 false */
    @Value("${merchanthub.order.tx.debug.report-unknown:false}")
    private boolean reportUnknown;

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        LocalTx tx = (LocalTx) arg;
        if (!tx.run()) {
            // 本地事务已回滚 → 删掉半消息，消费者永远看不到
            log.warn("本地事务失败，回滚半消息 keys={}", msg.getKeys(), tx.failure());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        // ---- 走到这里：订单已提交到数据库，但 Broker 还不知道 ----
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
            // 消息体坏了：无法判断也无法消费，回滚丢弃，留日志人工看
            log.error("事务回查：消息体无法解析，回滚 msgId={} keys={}", msg.getMsgId(), msg.getKeys(), e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        boolean exists;
        try {
            // 按订单号判断本地事务结果。能这样判断的前提：回查时本地事务一定已经结束
            // （本地事务超时 5s < 免回查时间 30s，B2 笔记 Q2）
            // 回查线程是 RocketMQ 的线程，没有租户上下文：按事件里的商家查（同时也校验了订单确实属于这个商家）
            exists = TenantContext.callAs(TenantInfo.systemOf(event.tenantId()), () ->
                    ordersMapper.exists(new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, event.orderNo())));
        } catch (RuntimeException e) {
            // 查库失败 ≠ 订单不存在。这里返回 ROLLBACK 会把一个可能已经建好的订单的消息删掉；
            // 返回 UNKNOW 让 Broker 过一会儿再来问
            log.warn("事务回查：查库失败，稍后重查 orderNo={} tenantId={} checkTimes={}",
                    event.orderNo(), event.tenantId(), checkTimes, e);
            return LocalTransactionState.UNKNOW;
        }
        long ageMs = clock.millis() - msg.getBornTimestamp();
        if (!exists && ageMs < rollbackMinAgeSeconds * 1000) {
            // 本地事务可能还没结束：现在判回滚，万一它稍后提交了，就是"订单建了消息没了"
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
