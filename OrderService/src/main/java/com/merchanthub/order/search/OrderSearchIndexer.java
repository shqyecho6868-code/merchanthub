package com.merchanthub.order.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.order.mapper.OrderItemMapper;
import com.merchanthub.order.mapper.OrdersMapper;
import com.merchanthub.order.model.entity.OrderItem;
import com.merchanthub.order.model.entity.Orders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Syncs one order to ES (E2).
 * <p>
 * Key design: <b>binlog is only a signal that "this order changed"; the document is rebuilt from the latest MySQL data and fully overwritten</b>.
 * <ul>
 *   <li>Order-independent: whichever of two binlog events is processed first, what ends up written is the DB's current value</li>
 *   <li>Duplicate-safe: retries after sync failures and Canal redeliveries are naturally idempotent with full overwrites</li>
 *   <li>Order header and items are two tables and two binlog events; one lookup assembles the complete document</li>
 * </ul>
 * The cost is one extra primary-key lookup per change, perfectly acceptable for low-frequency writes like orders.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSearchIndexer {

    public static final String INDEX = "orders";

    private final ElasticsearchClient es;
    private final OrdersMapper ordersMapper;
    private final OrderItemMapper itemMapper;

    private volatile boolean indexReady;

    /**
     * Create the index on first use with an explicit mapping: don't let ES infer it (orderNo would become long, product names would get an extra keyword sub-field).
     * itemNames uses the default standard analyzer: Chinese is split per character, enough to demo "search by product name"; IK analyzer tuning is out of scope
     */
    void ensureIndex() throws IOException {
        if (indexReady) {
            return;
        }
        if (!es.indices().exists(e -> e.index(INDEX)).value()) {
            try {
                es.indices().create(c -> c.index(INDEX).mappings(m -> m.properties(Map.of(
                        "orderNo", Property.of(p -> p.keyword(k -> k)),
                        "tenantId", Property.of(p -> p.long_(l -> l)),
                        "buyerId", Property.of(p -> p.long_(l -> l)),
                        "status", Property.of(p -> p.integer(i -> i)),
                        "payAmount", Property.of(p -> p.long_(l -> l)),
                        "createdAt", Property.of(p -> p.date(d -> d.format("epoch_millis"))),
                        "itemNames", Property.of(p -> p.text(t -> t)),
                        "skuSpecs", Property.of(p -> p.text(t -> t))))));
                log.info("ES 索引 {} 已创建", INDEX);
            } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
                // Multiple instances creating the index at once: someone else got there first, not an error
                if (!"resource_already_exists_exception".equals(e.error().type())) {
                    throw e;
                }
            }
        }
        indexReady = true;
    }

    /** Sync by order number: order exists -> full overwrite; doesn't exist (deleted / soft-deleted) -> delete the document */
    public void sync(long orderNo) throws IOException {
        ensureIndex();
        // The sync thread belongs to no request and reads orders across merchants: SYSTEM identity
        OrderSearchDoc doc = TenantContext.callAsSystem(() -> load(orderNo));
        if (doc == null) {
            es.delete(d -> d.index(INDEX).id(String.valueOf(orderNo)));
            log.info("ES 同步：订单不存在，删除文档 orderNo={}", orderNo);
            return;
        }
        es.index(i -> i.index(INDEX).id(doc.orderNo()).document(doc));
        log.debug("ES 同步完成 orderNo={} status={}", orderNo, doc.status());
    }

    private OrderSearchDoc load(long orderNo) {
        Orders o = ordersMapper.selectByOrderNoAcrossTenants(orderNo);
        if (o == null) {
            return null;
        }
        List<OrderItem> items = itemMapper.selectList(new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderNo, orderNo));
        return new OrderSearchDoc(String.valueOf(o.getOrderNo()), o.getTenantId(), o.getBuyerId(), o.getStatus(),
                o.getPayAmount(), o.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
                items.stream().map(OrderItem::getProductName).toList(),
                items.stream().map(OrderItem::getSkuSpec).toList());
    }
}
