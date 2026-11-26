package com.merchanthub.order;

import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.order.client.ProductInventoryClient;
import com.merchanthub.order.client.dto.SkuQueryRequest;
import com.merchanthub.order.client.dto.SkuSnapshot;
import com.merchanthub.order.config.SentinelRulesConfig;
import com.merchanthub.order.model.dto.CreateOrderRequest;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.service.OrderCreateService;
import com.merchanthub.testsupport.IntegrationTestBase;
import com.merchanthub.testsupport.SharedContainers;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import org.junit.jupiter.api.AfterEach;
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
 * Shared fixture for order integration tests: real MySQL / Redis / RocketMQ, with a Mockito stand-in for product-inventory.
 * Before each case the order tables are cleared and the product catalog is reset to two SKUs.
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

    @Autowired
    private SentinelRulesConfig sentinelRules;

    protected final Map<Long, SkuSnapshot> catalog = new HashMap<>();

    @BeforeAll
    static void createTopics() {
        SharedContainers.rocketmq().createTopic(OrderEventTopics.TOPIC);
        SharedContainers.rocketmq().createTopic(OrderEventTopics.TIMEOUT_TOPIC);
    }

    private TenantContext.Scope tenantScope;

    /**
     * C1: the tenant plugin is fail-closed; a thread without context touching business tables throws. These cases call services directly on the test thread (bypassing
     * the Web / MQ entry points), so by default the test thread gets a cross-tenant SYSTEM identity; tenant isolation itself is verified by TenantIsolationIT.
     * For cases going through real MQ, the consumer thread's context is still restored from the tenantId in the message, unaffected by this.
     */
    /**
     * MockMvc cases override this to false: MockMvc runs requests on the test thread, and an existing SYSTEM identity there would make
     * "requests without identity headers" look authenticated, breaking the 401 / 403 cases
     */
    protected boolean defaultSystemContext() {
        return true;
    }

    @BeforeEach
    void openSystemTenantScope() {
        tenantScope = defaultSystemContext() ? TenantContext.open(TenantInfo.system()) : null;
    }

    @AfterEach
    void closeTenantScope() {
        if (tenantScope != null) {
            tenantScope.close();
        }
    }

    /** C2: Sentinel rules / breaker state are JVM-global; reset per case from this context's config so an "open breaker" left by another test class doesn't leak in */
    @BeforeEach
    void resetSentinel() {
        sentinelRules.reload();
    }

    @BeforeEach
    void resetFixture() {
        for (String t : tablesToTruncate()) {
            jdbc.execute("TRUNCATE TABLE " + t);
        }
        catalog.clear();
        catalog.put(101L, sku(101, TENANT, 9900, "{\"color\": \"white\", \"size\": \"L\"}"));
        catalog.put(102L, sku(102, TENANT, 1999, "{\"color\": \"black\"}"));
        when(client.querySkus(any())).thenAnswer(inv -> {
            List<Long> ids = inv.<SkuQueryRequest>getArgument(0).getSkuIds();
            return ResultUtils.success(ids.stream().filter(catalog::containsKey).map(catalog::get).toList());
        });
        when(client.reserve(any())).thenReturn(ResultUtils.success(null));
    }

    protected List<String> tablesToTruncate() {
        return List.of("orders", "order_item", "trade_snapshot", "order_status_log", "order_outbox",
                "consumed_event", "pay_anomaly", "recon_exception", "order_outbox_archive");
    }

    protected static SkuSnapshot sku(long id, long tenant, long price, String spec) {
        SkuSnapshot s = new SkuSnapshot();
        s.setSkuId(id);
        s.setTenantId(tenant);
        s.setProductId(id * 10);
        s.setProductName("Product " + id);
        s.setSpecJson(spec);
        s.setPrice(price);
        s.setSkuStatus(SkuSnapshot.SKU_ON_SALE);
        s.setProductStatus(SkuSnapshot.PRODUCT_LISTED);
        return s;
    }

    /** Order request; tenantId is the first SKU's merchant (TENANT if the SKU isn't in the catalog), matching a real frontend's "which store you're ordering in" */
    protected CreateOrderRequest req(Long expected, long... skuQty) {
        List<CreateOrderRequest.Item> items = new ArrayList<>();
        for (int i = 0; i < skuQty.length; i += 2) {
            items.add(new CreateOrderRequest.Item(skuQty[i], (int) skuQty[i + 1]));
        }
        SkuSnapshot first = catalog.get(skuQty[0]);
        return new CreateOrderRequest(items, expected, first == null ? TENANT : first.getTenantId());
    }

    protected long countOrders(long orderNo) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_no = ?", Long.class, orderNo);
        return n == null ? 0 : n;
    }
}
