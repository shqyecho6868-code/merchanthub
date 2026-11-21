package com.merchanthub.order;

import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.order.client.ProductInventoryClient;
import com.merchanthub.order.client.dto.SkuQueryRequest;
import com.merchanthub.order.client.dto.SkuSnapshot;
import com.merchanthub.order.model.dto.CreateOrderRequest;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.service.OrderCreateService;
import com.merchanthub.testsupport.IntegrationTestBase;
import com.merchanthub.testsupport.SharedContainers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * order 集成测试公共夹具：真实 MySQL / Redis / RocketMQ，product-inventory 用 Mockito 替身。
 * 每个用例前清空订单相关表，商品目录重置为两个 SKU。
 */
abstract class OrderItSupport extends IntegrationTestBase {

    protected static final long TENANT = 7L;
    protected static final long BUYER = 1001L;

    @MockitoBean
    protected ProductInventoryClient client;

    @Autowired
    protected OrderCreateService orderCreateService;

    @Autowired
    protected JdbcTemplate jdbc;

    protected final Map<Long, SkuSnapshot> catalog = new HashMap<>();

    @BeforeAll
    static void createTopics() {
        SharedContainers.rocketmq().createTopic(OrderEventTopics.TOPIC);
        SharedContainers.rocketmq().createTopic(OrderEventTopics.TIMEOUT_TOPIC);
    }

    @BeforeEach
    void resetFixture() {
        for (String t : tablesToTruncate()) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        catalog.clear();
        catalog.put(101L, sku(101, TENANT, 9900, "{\"颜色\": \"白\", \"尺码\": \"L\"}"));
        catalog.put(102L, sku(102, TENANT, 1999, "{\"颜色\": \"黑\"}"));
        when(client.querySkus(any())).thenAnswer(inv -> {
            List<Long> ids = inv.<SkuQueryRequest>getArgument(0).getSkuIds();
            return ResultUtils.success(ids.stream().filter(catalog::containsKey).map(catalog::get).toList());
        });
        when(client.reserve(any())).thenReturn(ResultUtils.success(null));
    }

    protected List<String> tablesToTruncate() {
        return List.of("orders", "order_item", "trade_snapshot", "order_status_log", "order_outbox",
                "consumed_event", "merchant_order_notice", "pay_anomaly");
    }

    protected static SkuSnapshot sku(long id, long tenant, long price, String spec) {
        SkuSnapshot s = new SkuSnapshot();
        s.setSkuId(id);
        s.setTenantId(tenant);
        s.setProductId(id * 10);
        s.setProductName("商品" + id);
        s.setSpecJson(spec);
        s.setPrice(price);
        s.setSkuStatus(SkuSnapshot.SKU_ON_SALE);
        s.setProductStatus(SkuSnapshot.PRODUCT_LISTED);
        return s;
    }

    protected static CreateOrderRequest req(Long expected, long... skuQty) {
        List<CreateOrderRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) {
            items.add(new CreateOrderRequest.Item(skuQty[i], (int) skuQty[i + 1]));
        }
        return new CreateOrderRequest(items, expected);
    }

    protected long countOrders(long orderNo) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_no = ?", Long.class, orderNo);
        return n == null ? 0 : n;
    }
}
