package com.merchanthub.order.mq.timeout;

import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.order.mapper.ConsumedEventMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.model.entity.ConsumedEvent;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.model.enums.OrderEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.mq.OrderSettledEvent;
import com.merchanthub.order.mq.outbox.OutboxWriter;
import com.merchanthub.order.service.OrderStatusTransitioner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Timeout close: scheduled message fires -> CAS pending -> closed -> only the winner writes the "order closed" event (inventory is released from it).
 * <p>
 * When racing payment success on the same order, the CAS in {@link OrderStatusTransitioner} lets only one side win:
 * the loser writes nothing, so it produces no inventory event -- "the loser doesn't touch inventory" isn't left to the loser's discipline; it simply has no event to send.
 * <p>
 * Two entry points share {@link #closeIfPending}:
 * <ul>
 *   <li>{@link #handle}: ORDER_TIMEOUT scheduled message (main path), deduplicated first</li>
 *   <li>{@link #closeExpired}: timeout-order scan (safety net: scheduled message lost / sent to DLQ), no dedup needed -- the CAS itself is the idempotency gate</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayTimeoutService {

    public static final String CONSUMER_GROUP = "order-pay-timeout";

    private final ConsumedEventMapper consumedEventMapper;
    private final OrdersMapper ordersMapper;
    private final OrderStatusTransitioner transitioner;
    private final OutboxWriter outboxWriter;
    private final OrderEventCodec codec;
    private final SnowflakeIdGenerator idGenerator;

    /** @return true if the order was closed this time; false for a duplicate message or an order no longer pending (paid / cancelled), nothing changed */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(PayTimeoutMessage msg) {
        ConsumedEvent mark = new ConsumedEvent();
        mark.setTenantId(msg.tenantId());
        mark.setConsumerGroup(CONSUMER_GROUP);
        mark.setEventId(msg.eventId());
        try {
            consumedEventMapper.insert(mark);
        } catch (DuplicateKeyException e) {
            log.info("Duplicate timeout-close message, skipping eventId={} orderNo={} tenantId={}", msg.eventId(), msg.orderNo(), msg.tenantId());
            return false;
        }
        return closeIfPending(msg.orderNo(), msg.tenantId(), "payment timeout");
    }

    /** Called by the timeout-order scan: the order is long past its payment deadline but still pending */
    @Transactional(rollbackFor = Exception.class)
    public boolean closeExpired(long orderNo, long tenantId) {
        return closeIfPending(orderNo, tenantId, "payment timeout (scan fallback)");
    }

    /** Called only from the two transactional methods above (self-invocation bypasses the transaction annotation, so it isn't annotated; transitioner is MANDATORY and fails fast without a transaction) */
    private boolean closeIfPending(long orderNo, long tenantId, String remark) {
        boolean won = transitioner.transit(orderNo, tenantId, OrderEvent.PAY_TIMEOUT, "SYSTEM", 0L, remark);
        if (!won) {
            // Usually: already paid (payment success won the CAS first). Return normally: no close, no event, no inventory change
            log.info("[Timeout close: CAS lost] Order no longer pending, not closing or releasing inventory orderNo={} tenantId={}", orderNo, tenantId);
            return false;
        }
        // Include the buyer so realtime can push "order closed". If the CAS won, the order definitely exists
        Orders order = ordersMapper.selectByOrderNoAcrossTenants(orderNo);
        Long buyerId = order == null ? null : order.getBuyerId();
        // Write the "closed" event in the same transaction as the close CAS: once the close commits, inventory release is guaranteed (if the process dies, the scan re-sends)
        OrderSettledEvent closed = new OrderSettledEvent(idGenerator.nextId(), orderNo, tenantId,
                OrderEvent.PAY_TIMEOUT.name(), buyerId);
        outboxWriter.append(tenantId, closed.eventId(), String.valueOf(orderNo), OrderEventTopics.TOPIC,
                OrderEventTopics.TAG_ORDER_CLOSED, codec.toJson(closed), null);
        log.info("[Timeout close: CAS won] pending -> closed, ORDER_CLOSED event written (inventory will be released) orderNo={} tenantId={} remark={}",
                orderNo, tenantId, remark);
        return true;
    }
}
