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
 * 把一个订单同步到 ES（E2）。
 * <p>
 * 关键设计：<b>binlog 只当"这个订单变了"的信号，文档内容回 MySQL 查最新值再整篇覆盖</b>。
 * <ul>
 *   <li>不怕乱序：先后两条 binlog 谁先处理都一样，最后写进去的都是数据库当前值</li>
 *   <li>不怕重复：同步失败重试、Canal 重复投递，整篇覆盖天然幂等</li>
 *   <li>订单头和明细是两张表、两条 binlog，回查一次就能拼成完整文档</li>
 * </ul>
 * 代价是每次变更多一次按主键回查，对订单这种低频写入完全可以接受。
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
     * 首次使用时建索引并显式给出 mapping：不让 ES 自动推断（orderNo 会被推断成 long、商品名会多出一份 keyword 子字段）。
     * itemNames 用默认 standard 分词器：中文按单字切，够演示"按商品名搜"；IK 分词等调优不在范围内
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
                // 多实例同时建索引：别人先建好了，不算错
                if (!"resource_already_exists_exception".equals(e.error().type())) {
                    throw e;
                }
            }
        }
        indexReady = true;
    }

    /** 按订单号同步：订单存在 → 整篇覆盖；不存在（被删 / 逻辑删除）→ 删文档 */
    public void sync(long orderNo) throws IOException {
        ensureIndex();
        // 同步线程不属于任何请求，跨商家读订单：SYSTEM 身份
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
