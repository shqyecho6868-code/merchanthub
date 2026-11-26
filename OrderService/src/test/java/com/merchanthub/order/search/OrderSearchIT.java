package com.merchanthub.order.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.order.client.ProductInventoryClient;
import com.merchanthub.order.client.dto.SkuSnapshot;
import com.merchanthub.order.model.dto.CreateOrderRequest;
import com.merchanthub.order.service.OrderCreateService;
import com.merchanthub.testsupport.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.elasticsearch.ElasticsearchContainer;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * E2 (demo only): after orders sync to ES, search by merchant + keyword. The Canal part (reading the binlog) isn't started in tests;
 * we call indexer.sync(orderNo) directly -- exactly what the Canal thread does once it has the order number (extracting order numbers is covered by CanalOrderSyncTest).
 * <p>
 * Focus on <b>tenant isolation</b>: ES bypasses the MyBatis tenant plugin, so the tenantId filter must be added by hand; merchant 8 can't find merchant 7's orders.
 */
@SpringBootTest
class OrderSearchIT extends IntegrationTestBase {

    // Image version matches docker-compose; security disabled (local / tests need no TLS or password)
    static final ElasticsearchContainer ES = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.18.8")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(Duration.ofMinutes(3));

    static {
        ES.start();
    }

    @DynamicPropertySource
    static void esProps(DynamicPropertyRegistry r) {
        r.add("spring.elasticsearch.uris", () -> "http://" + ES.getHttpHostAddress());
    }

    private static final long TENANT = 7L;

    @MockitoBean
    private ProductInventoryClient client;
    @Autowired
    private OrderCreateService orderCreateService;
    @Autowired
    private OrderSearchIndexer indexer;
    @Autowired
    private OrderSearchService searchService;
    @Autowired
    private ElasticsearchClient es;
    @Autowired
    private JdbcTemplate jdbc;

    private TenantContext.Scope scope;

    @BeforeEach
    void setUp() {
        scope = TenantContext.open(TenantInfo.system());
        SkuSnapshot s = new SkuSnapshot();
        s.setSkuId(101L);
        s.setTenantId(TENANT);
        s.setProductId(1010L);
        s.setProductName("White cotton short-sleeve T-shirt");
        s.setSpecJson("{\"color\": \"white\", \"size\": \"L\"}");
        s.setPrice(9900L);
        s.setSkuStatus(SkuSnapshot.SKU_ON_SALE);
        s.setProductStatus(SkuSnapshot.PRODUCT_LISTED);
        when(client.querySkus(any())).thenReturn(ResultUtils.success(List.of(s)));
        when(client.reserve(any())).thenReturn(ResultUtils.success(null));
    }

    @AfterEach
    void tearDown() {
        scope.close();
    }

    private long order() {
        return Long.parseLong(orderCreateService.createOrder(1001L,
                new CreateOrderRequest(List.of(new CreateOrderRequest.Item(101L, 1)), null, TENANT)).getOrderNo());
    }

    private void refresh() throws Exception {
        es.indices().refresh(r -> r.index(OrderSearchIndexer.INDEX));   // make just-written documents searchable immediately (normally 1s refresh delay)
    }

    @Test
    void syncThenSearch_byKeywordAndOrderNo_isolatedByTenant() throws Exception {
        long orderNo = order();
        indexer.sync(orderNo);
        indexer.sync(orderNo);   // duplicate sync (Canal redelivery): full overwrite, same result
        refresh();

        List<OrderSearchDoc> hits = searchService.search(TENANT, "cotton", 1, 20);
        assertThat(hits).extracting(OrderSearchDoc::orderNo).contains(String.valueOf(orderNo));
        assertThat(searchService.search(TENANT, String.valueOf(orderNo), 1, 20)).hasSize(1);
        assertThat(searchService.search(TENANT, "", 1, 20)).isNotEmpty();
        // Merchant 8 searching with the same keyword can't see merchant 7's order
        assertThat(searchService.search(8L, "cotton", 1, 20)).isEmpty();

        // Order status changed -> sync again, the document follows (latest value looked up from the DB)
        jdbc.update("UPDATE orders SET status = 50 WHERE order_no = ?", orderNo);
        indexer.sync(orderNo);
        refresh();
        assertThat(searchService.search(TENANT, String.valueOf(orderNo), 1, 20).get(0).status()).isEqualTo(50);

        // Order row deleted (soft delete) -> document deleted
        jdbc.update("UPDATE orders SET deleted = 1 WHERE order_no = ?", orderNo);
        indexer.sync(orderNo);
        refresh();
        assertThat(searchService.search(TENANT, String.valueOf(orderNo), 1, 20)).isEmpty();
    }

    @Test
    void searchFails_gracefullyWhenIndexMissing() throws Exception {
        es.indices().delete(d -> d.index(OrderSearchIndexer.INDEX).ignoreUnavailable(true));
        // ES is a query replica: if it can't be queried, degrade to a clear business error without affecting transactional paths like order placement
        assertThatThrownBy(() -> searchService.search(TENANT, "x", 1, 20)).isInstanceOf(BusinessException.class);
        // Hand the index mapping back to the indexer for rebuilding (it caches an "index ready" flag; clear it so the next case recreates it)
        org.springframework.test.util.ReflectionTestUtils.setField(indexer, "indexReady", false);
    }
}
