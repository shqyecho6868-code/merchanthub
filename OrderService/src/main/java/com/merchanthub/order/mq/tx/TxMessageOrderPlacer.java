package com.merchanthub.order.mq.tx;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.order.mq.OrderCreatedEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import com.merchanthub.order.service.NewOrder;
import com.merchanthub.order.service.OrderPersistService;
import com.merchanthub.order.service.OrderPlacer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.client.producer.TransactionSendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 方案一：RocketMQ 事务消息。
 * <pre>
 * ① 发半消息（消费者看不到） ──失败──▶ 订单不建，下单失败
 *        │成功
 * ② 执行本地事务：写订单（OrderTxListener.executeLocalTransaction）
 *        │
 * ③ 成功 → COMMIT（消费者可见）；失败 → ROLLBACK（半消息删除）
 *        │如果 ③ 没送到 Broker（进程挂了）
 * ④ Broker 回查 → 按订单是否存在决定 COMMIT / ROLLBACK（OrderTxListener.checkLocalTransaction）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "merchanthub.order.event-mode", havingValue = "TX_MESSAGE")
public class TxMessageOrderPlacer implements OrderPlacer {

    private final TransactionMQProducer producer;
    private final OrderPersistService persistService;
    private final OrderEventCodec codec;
    private final SnowflakeIdGenerator idGenerator;

    /** 免回查时间：必须大于本地事务超时（OrderPersistService 的 5 秒），回查时本地事务才一定有结果（B2 笔记 Q2） */
    @Value("${merchanthub.order.tx.check-immunity-seconds:30}")
    private int checkImmunitySeconds;

    @Override
    public void place(NewOrder order) {
        OrderCreatedEvent event = OrderCreatedEvent.of(idGenerator.nextId(), order);
        Message msg = codec.toMessage(event);
        msg.putUserProperty(MessageConst.PROPERTY_CHECK_IMMUNITY_TIME_IN_SECONDS, String.valueOf(checkImmunitySeconds));

        LocalTx tx = new LocalTx(() -> persistService.saveNewOrder(order));
        TransactionSendResult result;
        try {
            result = producer.sendMessageInTransaction(msg, tx);
        } catch (MQClientException e) {
            // 半消息都没发出去（MQ 不可用）：本地事务没执行，订单不建。
            // 这是事务消息方案的代价：MQ 挂了就不能下单（库存已预占 → 悬空预占，B3 补偿）
            log.error("半消息发送失败，下单失败 orderNo={} tenantId={}", order.orderNo(), order.tenantId(), e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (tx.failure() != null) {
            throw tx.failure();   // 本地事务失败：原样抛出，保留真实原因
        }
        if (!tx.executed()) {
            log.error("半消息未写入成功 status={} orderNo={} tenantId={}",
                    result.getSendStatus(), order.orderNo(), order.tenantId());
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        // 到这里订单一定已提交。COMMIT 是单向通知，即使没送到，Broker 回查也会补上，所以不用再判断
        log.info("订单已落库，事务消息已提交 orderNo={} tenantId={} eventId={} msgId={} state={}",
                order.orderNo(), order.tenantId(), event.eventId(), result.getMsgId(), result.getLocalTransactionState());
    }
}
