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
 * Option 1: RocketMQ transactional message.
 * <pre>
 * (1) Send half message (invisible to consumers) --fail--> order not created, placement fails
 *        | success
 * (2) Run the local transaction: write the order (OrderTxListener.executeLocalTransaction)
 *        │
 * (3) Success -> COMMIT (visible to consumers); failure -> ROLLBACK (half message deleted)
 *        | if (3) never reaches the broker (process died)
 * (4) Broker checks back -> COMMIT / ROLLBACK depending on whether the order exists (OrderTxListener.checkLocalTransaction)
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

    /** Check-immunity time: must exceed the local transaction timeout (5s in OrderPersistService) so the local transaction always has a result at check-back (B2 notes Q2) */
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
            // The half message didn't even go out (MQ unavailable): the local transaction never ran, no order created.
            // This is the cost of the transactional-message approach: no orders while MQ is down (inventory already reserved -> dangling reservation, compensated in B3)
            log.error("Failed to send half message, order placement failed orderNo={} tenantId={}", order.orderNo(), order.tenantId(), e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (tx.failure() != null) {
            throw tx.failure();   // Local transaction failed: rethrow as-is to keep the real cause
        }
        if (!tx.executed()) {
            log.error("Half message not stored successfully status={} orderNo={} tenantId={}",
                    result.getSendStatus(), order.orderNo(), order.tenantId());
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        // By now the order is definitely committed. COMMIT is a one-way notification; even if it's lost, the broker check-back fills it in, so no further check needed
        log.info("Order persisted, transactional message committed orderNo={} tenantId={} eventId={} msgId={} state={}",
                order.orderNo(), order.tenantId(), event.eventId(), result.getMsgId(), result.getLocalTransactionState());
    }
}
