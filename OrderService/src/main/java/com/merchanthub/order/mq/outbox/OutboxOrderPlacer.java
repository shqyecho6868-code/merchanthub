package com.merchanthub.order.mq.outbox;

import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import com.merchanthub.order.mq.OrderCreatedEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.service.NewOrder;
import com.merchanthub.order.service.OrderPersistService;
import com.merchanthub.order.service.OrderPlacer;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 方案二：本地消息表。
 * <pre>
 * 【一个本地事务】写订单（OrderPersistService，加入本事务） + 写一行 order_outbox(status=0)
 *        │提交后
 * 立即异步发一次（快路径，绝大多数消息几十毫秒内送达）
 *        │发失败 / 进程挂了 / 线程池满了
 * OutboxRelay 定时扫描 status=0 且到期的记录补发（兜底）
 * </pre>
 * 一致性只依赖 MySQL 本地事务："有订单就一定有待发消息，没订单就一定没有"。MQ 挂了也不影响下单。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "merchanthub.order.event-mode", havingValue = "OUTBOX")
public class OutboxOrderPlacer implements OrderPlacer {

    private final OrderPersistService persistService;
    private final OrderOutboxMapper outboxMapper;
    private final OrderEventCodec codec;
    private final SnowflakeIdGenerator idGenerator;
    private final OutboxRelay relay;

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 5)
    public void place(NewOrder order) {
        persistService.saveNewOrder(order);   // 传播行为 REQUIRED：加入当前事务，而不是自己单独提交

        OrderCreatedEvent event = OrderCreatedEvent.of(idGenerator.nextId(), order);
        OrderOutbox row = new OrderOutbox();
        row.setTenantId(order.tenantId());
        row.setEventId(event.eventId());
        row.setBizKey(String.valueOf(order.orderNo()));
        row.setTopic(OrderEventTopics.TOPIC);
        row.setTag(OrderEventTopics.TAG_ORDER_CREATED);
        row.setPayload(codec.toJson(event));
        row.setStatus(OrderOutbox.PENDING);
        row.setRetryCount(0);
        row.setNextRetryAt(order.createdAt());
        outboxMapper.insert(row);

        // 必须等事务提交后再发：在事务里发，消息出去了事务却可能回滚 → "消息发了订单没建"
        long outboxId = row.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                relay.publishAsync(outboxId);
            }
        });
    }
}
