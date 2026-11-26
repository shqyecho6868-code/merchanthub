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
 * Option 2: local message table (outbox).
 * <pre>
 * [One local transaction] write the order (OrderPersistService, joins this transaction) + write an order_outbox row (status=0)
 *        | after commit
 * Send once immediately and asynchronously (fast path; the vast majority of messages arrive within tens of ms)
 *        | send fails / process dies / thread pool full
 * OutboxRelay periodically scans due status=0 records and re-sends them (safety net)
 * </pre>
 * Consistency depends only on the MySQL local transaction: "if there's an order there's a pending message, if not there isn't". MQ being down doesn't block order placement.
 * The generic outbox-writing part is extracted into {@link OutboxWriter}, which the order's other events also use.
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
        persistService.saveNewOrder(order);   // propagation REQUIRED: joins the current transaction instead of committing on its own

        OrderCreatedEvent event = OrderCreatedEvent.of(idGenerator.nextId(), order);
        outboxWriter.append(order.tenantId(), event.eventId(), String.valueOf(order.orderNo()),
                OrderEventTopics.TOPIC, OrderEventTopics.TAG_ORDER_CREATED, codec.toJson(event), null);
    }
}
