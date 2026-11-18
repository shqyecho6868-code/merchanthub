package com.merchanthub.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 事务 B：快照 + 明细 + 订单 + 状态流水，要么全写入，要么全不写。
 * <p>
 * 单独成一个 Bean 是为了让 @Transactional 生效：Spring 事务靠代理实现，
 * 如果放在 OrderCreateService 里被同类方法调用（this.xxx()），会绕过代理，事务不生效。
 */
@Service
@RequiredArgsConstructor
public class OrderPersistService {

    private final OrdersMapper ordersMapper;
    private final OrderItemMapper orderItemMapper;
    private final TradeSnapshotMapper tradeSnapshotMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public void saveNewOrder(long orderNo, long tenantId, long buyerId, OrderPricing.Result pricing, LocalDateTime expireAt) {
        for (OrderPricing.Line line : pricing.lines()) {
            SkuSnapshot sku = line.sku();

            // 完整快照（点“查看快照”时才读）：商家以后改价、改描述、删商品，都不影响这份记录
            TradeSnapshot snapshot = new TradeSnapshot();
            snapshot.setTenantId(tenantId);
            snapshot.setOrderNo(orderNo);
            snapshot.setSkuId(sku.getSkuId());
            snapshot.setContent(toJson(sku));
            tradeSnapshotMapper.insert(snapshot);

            // 明细行冗余关键字段（列表页直接用，不回查商品服务）
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
        order.setExpireAt(expireAt);
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

    /** {"颜色":"白","尺码":"L"} → "白 / L" */
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
