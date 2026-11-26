package com.merchanthub.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.id.SnowflakeIdGenerator;
import com.merchanthub.order.client.dto.SkuSnapshot;
import com.merchanthub.order.mapper.OrderItemMapper;
import com.merchanthub.order.mapper.OrderStatusLogMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.mapper.TradeSnapshotMapper;
import com.merchanthub.order.model.entity.OrderItem;
import com.merchanthub.order.model.entity.OrderStatusLog;
import com.merchanthub.order.model.entity.Orders;
import com.merchanthub.order.model.entity.TradeSnapshot;
import com.merchanthub.order.model.enums.OrderStatus;
import com.merchanthub.order.mq.OrderEventCodec;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.mq.outbox.OutboxWriter;
import com.merchanthub.order.mq.timeout.PayTimeoutMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Transaction B: snapshot + items + order + status log, all written or none.
 * <p>
 * It's a separate Bean so @Transactional takes effect: Spring transactions work via proxies,
 * and a self-invocation from within OrderCreateService (this.xxx()) would bypass the proxy and the transaction.
 */
@Service
@RequiredArgsConstructor
public class OrderPersistService {

    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final TradeSnapshotMapper tradeSnapshotMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final ObjectMapper objectMapper;
    private final OutboxWriter outboxWriter;
    private final OrderEventCodec codec;
    private final SnowflakeIdGenerator idGenerator;

    /**
     * timeout = 5: the local transaction runs at most 5s and always rolls back on timeout. The transactional message's check-immunity time (30s) must be longer,
     * so the local transaction has always finished when the broker checks back, and "order not found" can safely be judged a rollback.
     */
    @Transactional(rollbackFor = Exception.class, timeout = 5)
    public void saveNewOrder(NewOrder newOrder) {
        long orderNo = newOrder.orderNo();
        long tenantId = newOrder.tenantId();
        long buyerId = newOrder.buyerId();
        OrderPricing.Result pricing = newOrder.pricing();
        for (OrderPricing.Line line : pricing.lines()) {
            SkuSnapshot sku = line.sku();

            // Full snapshot (read only on "view snapshot"): later price changes, description edits, or product deletion by the merchant don't affect this record
            TradeSnapshot snapshot = new TradeSnapshot();
            snapshot.setTenantId(tenantId);
            snapshot.setOrderNo(orderNo);
            snapshot.setSkuId(sku.getSkuId());
            snapshot.setContent(toJson(sku));
            tradeSnapshotMapper.insert(snapshot);

            // Items denormalize key fields (used directly by list pages without calling the product service)
            OrderItem item = new OrderItem();
            item.setTenantId(tenantId);
            item.setOrderNo(orderNo);
            item.setProductId(sku.getProductId());
            item.setSkuId(sku.getSkuId());
            item.setProductName(sku.getProductName());
            item.setSkuSpec(specText(sku.getSpecJson()));
            item.setUnitPrice(sku.getPrice());
            item.setQuantity(line.quantity());
            item.setTotalAmount(line.totalAmount());
            item.setDiscountAmount(line.discountAmount());
            item.setPayAmount(line.payAmount());
            item.setSnapshotId(snapshot.getId());
            orderItemMapper.insert(item);
        }

        Orders order = new Orders();
        order.setTenantId(tenantId);
        order.setOrderNo(orderNo);
        order.setBuyerId(buyerId);
        order.setStatus(OrderStatus.PENDING_PAYMENT.getCode());
        order.setTotalAmount(pricing.totalAmount());
        order.setDiscountAmount(pricing.discountAmount());
        order.setPayAmount(pricing.payAmount());
        order.setExpireAt(newOrder.expireAt());
        order.setCreatedAt(newOrder.createdAt());
        ordersMapper.insert(order);

        OrderStatusLog log = new OrderStatusLog();
        log.setTenantId(tenantId);
        log.setOrderNo(orderNo);
        log.setFromStatus(null);
        log.setToStatus(OrderStatus.PENDING_PAYMENT.getCode());
        log.setEvent("CREATE");
        log.setOperatorType("BUYER");
        log.setOperatorId(buyerId);
        statusLogMapper.insert(log);

        // Timeout-close scheduled message: written to outbox in the same transaction as the order (both event-modes go through here).
        // Not sent directly at the end of createOrder: if the process died after commit but before send, the order would never time out and its inventory would stay reserved forever
        PayTimeoutMessage timeout = new PayTimeoutMessage(idGenerator.nextId(), orderNo, tenantId);
        outboxWriter.append(tenantId, timeout.eventId(), String.valueOf(orderNo), OrderEventTopics.TIMEOUT_TOPIC,
                OrderEventTopics.TAG_PAY_TIMEOUT, codec.toJson(timeout), newOrder.expireAt());
    }

    private String toJson(SkuSnapshot sku) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("skuId", sku.getSkuId());
        content.put("skuCode", sku.getSkuCode());
        content.put("productId", sku.getProductId());
        content.put("productName", sku.getProductName());
        content.put("description", sku.getProductDescription());
        content.put("mainImage", sku.getMainImage());
        content.put("spec", sku.getSpecJson());
        content.put("price", sku.getPrice());
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {"color":"white","size":"L"} -> "white / L" */
    String specText(String specJson) {
        try {
            Map<String, Object> spec = objectMapper.readValue(specJson, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return String.join(" / ", spec.values().stream().map(String::valueOf).toList());
        } catch (JsonProcessingException e) {
            return specJson;
        }
    }
}
