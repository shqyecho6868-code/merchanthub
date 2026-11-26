package com.merchanthub.order.mq.payment;

import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.order.mapper.ConsumedEventMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.mapper.PayAnomalyMapper;
import com.merchanthub.order.model.entity.ConsumedEvent;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.model.entity.PayAnomaly;
import com.merchanthub.order.model.enums.OrderEvent;
import com.merchanthub.order.model.enums.OrderStatus;
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

import java.util.Objects;

/**
 * On "payment succeeded", order itself marks the order as paid. payment never updates orders directly: order is the only writer of order status,
 * so every transition goes through the same state machine and CAS, and two services never update it independently.
 * <p>
 * Dedup record, status CAS, and status log are in the same local transaction (standard consumer-side idempotency).
 * CAS wins -> write the ORDER_PAID event in the same transaction (confirm inventory deduction); CAS loses -> write pay_anomaly in the same transaction (money received but order not paid, left for reconciliation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPaidService {

    public static final String CONSUMER_GROUP = "order-pay-success";

    private final ConsumedEventMapper consumedEventMapper;
    private final OrdersMapper ordersMapper;
    private final OrderStatusTransitioner transitioner;
    private final PayAnomalyMapper anomalyMapper;
    private final OutboxWriter outboxWriter;
    private final OrderEventCodec codec;
    private final SnowflakeIdGenerator idGenerator;

    /** @return true if the order was marked paid this time; false for a duplicate event or a disallowed order status, nothing changed */
    @Transactional(rollbackFor = Exception.class)
    public boolean handle(PaySuccessEvent event) {
        ConsumedEvent mark = new ConsumedEvent();
        mark.setTenantId(event.tenantId());
        mark.setConsumerGroup(CONSUMER_GROUP);
        mark.setEventId(event.eventId());
        try {
            consumedEventMapper.insert(mark);
        } catch (DuplicateKeyException e) {
            log.info("[Idempotency L3: consumer dedup] Duplicate payment-success event, skipping eventId={} payNo={} orderNo={} tenantId={}",
                    event.eventId(), event.payNo(), event.orderNo(), event.tenantId());
            return false;
        }

        Orders order = ordersMapper.selectByOrderNoAcrossTenants(event.orderNo());
        if (order == null) {
            // Callback before the order: the order may commit any moment. Throw -> transaction rolls back (dedup record undone too) -> RocketMQ redelivers (10s, 30s, 1m...).
            // If the order never appears: after 16 retries (about 4h46m total) it goes to the DLQ %DLQ%order-pay-success for reconciliation
            log.warn("[Callback before order] Order doesn't exist yet when payment-success event arrived, rolling back dedup record, will be redelivered payNo={} orderNo={} tenantId={}",
                    event.payNo(), event.orderNo(), event.tenantId());
            throw new OrderNotVisibleException(event.orderNo());
        }
        if (!Objects.equals(order.getTenantId(), event.tenantId()) || !Objects.equals(order.getPayAmount(), event.amount())) {
            // Merchant or amount mismatch: retrying won't fix it; record it for reconciliation and ack the message
            log.error("Payment-success event doesn't match the order, needs reconciliation payNo={} orderNo={} eventTenantId={} eventAmount={} order={}",
                    event.payNo(), event.orderNo(), event.tenantId(), event.amount(), order);
            recordAnomaly(event, PayAnomaly.MISMATCH, order.getStatus(), "orderTenantId=" + order.getTenantId()
                    + " orderAmount=" + order.getPayAmount());
            return false;
        }

        boolean ok = transitioner.transit(event.orderNo(), event.tenantId(), OrderEvent.PAY_SUCCESS,
                "SYSTEM", 0L, "payNo=" + event.payNo() + " channelTradeNo=" + event.channelTradeNo());
        if (!ok) {
            // E.g. money arrives after the order was closed for timeout, or two payments for one order both succeeded. The order can't be "revived" and inventory isn't touched;
            // just record a payment anomaly for reconciliation/refund (refunds are out of scope)
            // Must be a current read: a plain SELECT sees this transaction's starting snapshot (still pending), see OrdersMapper#selectStatusForUpdate
            int status = ordersMapper.selectStatusForUpdate(event.orderNo());
            String type = status == OrderStatus.CLOSED.getCode() ? PayAnomaly.PAID_AFTER_CLOSED : PayAnomaly.DUPLICATE_PAYMENT;
            log.error("[Idempotency L4: order CAS] Order status {} is not pending payment, payment success not applied, recorded payment anomaly {} for reconciliation payNo={} orderNo={} tenantId={}",
                    status, type, event.payNo(), event.orderNo(), event.tenantId());
            recordAnomaly(event, type, status, "channelTradeNo=" + event.channelTradeNo());
            return false;
        }
        // Write the "paid" event in the same transaction as the CAS: once the order is paid, inventory deduction confirmation is guaranteed to happen
        OrderSettledEvent paid = new OrderSettledEvent(idGenerator.nextId(), event.orderNo(), event.tenantId(),
                OrderEvent.PAY_SUCCESS.name(), order.getBuyerId());
        outboxWriter.append(event.tenantId(), paid.eventId(), String.valueOf(event.orderNo()), OrderEventTopics.TOPIC,
                OrderEventTopics.TAG_ORDER_PAID, codec.toJson(paid), null);
        return true;
    }

    private void recordAnomaly(PaySuccessEvent event, String type, Integer orderStatus, String remark) {
        PayAnomaly a = new PayAnomaly();
        a.setTenantId(event.tenantId());
        a.setPayNo(event.payNo());
        a.setOrderNo(event.orderNo());
        a.setAmount(event.amount());
        a.setType(type);
        a.setOrderStatus(orderStatus);
        a.setHandleStatus(0);
        a.setRemark(remark);
        anomalyMapper.insert(a);
    }
}
