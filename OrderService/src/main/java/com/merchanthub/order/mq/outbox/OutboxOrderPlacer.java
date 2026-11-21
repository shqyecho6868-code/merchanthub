package com.merchanthub.order.mq.outbox;

import com.merchanthub.common.id.SnowflakeIdGenerator;
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
 * B3-下起写 outbox 的通用部分抽到 {@link OutboxWriter}，订单的其他事件也走它。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "merchanthub.order.event-mode", havingValue = "OUTBOX")
public class OutboxOrderPlacer implements OrderPlacer {

    private final OrderPersistService persistService;
    private final OutboxWriter outboxWriter;
    private final OrderEventCodec codec;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 5)
    public void place(NewOrder order) {
        persistService.saveNewOrder(order);   // 传播行为 REQUIRED：加入当前事务，而不是自己单独提交

        OrderCreatedEvent event = OrderCreatedEvent.of(idGenerator.nextId(), order);
        outboxWriter.append(order.tenantId(), event.eventId(), String.valueOf(order.orderNo()),
                OrderEventTopics.TOPIC, OrderEventTopics.TAG_ORDER_CREATED, codec.toJson(event), null);
    }
}
