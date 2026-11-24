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
 * E2（只演示）：订单同步到 ES 后按商家 + 关键词搜。Canal 那一段（读 binlog）不在测试里起，
 * 直接调 indexer.sync(orderNo) —— Canal 线程拿到订单号后做的就是这一步（拆订单号由 CanalOrderSyncTest 覆盖）。
 * <p>
 * 重点验证<b>租户隔离</b>：ES 不经过 MyBatis 租户插件，tenantId 过滤必须自己加，商家 8 搜不到商家 7 的订单。
 */
@SpringBootTest
class OrderSearchIT extends IntegrationTestBase {

    // 镜像版本与 docker-compose 一致；关掉安全认证（本地 / 测试不需要 TLS 和密码）
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
        s.setProductName("白色纯棉短袖T恤");
        s.setSpecJson("{\"颜色\": \"白\", \"尺码\": \"L\"}");
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
        es.indices().refresh(r -> r.index(OrderSearchIndexer.INDEX));   // 让刚写入的文档立即可搜（正常有 1 秒刷新延迟）
    }

    @Test
    void syncThenSearch_byKeywordAndOrderNo_isolatedByTenant() throws Exception {
        long orderNo = order();
        indexer.sync(orderNo);
        indexer.sync(orderNo);   // 重复同步（Canal 重投）：整篇覆盖，结果不变
        refresh();

        List<OrderSearchDoc> hits = searchService.search(TENANT, "纯棉", 1, 20);
        assertThat(hits).extracting(OrderSearchDoc::orderNo).contains(String.valueOf(orderNo));
        assertThat(searchService.search(TENANT, String.valueOf(orderNo), 1, 20)).hasSize(1);
        assertThat(searchService.search(TENANT, "", 1, 20)).isNotEmpty();
        // 商家 8 用同样的关键词搜：看不到商家 7 的订单
        assertThat(searchService.search(8L, "纯棉", 1, 20)).isEmpty();

        // 订单状态变了 → 再同步一次，文档跟着变（回查数据库取最新值）
        jdbc.update("UPDATE orders SET status = 50 WHERE order_no = ?", orderNo);
        indexer.sync(orderNo);
        refresh();
        assertThat(searchService.search(TENANT, String.valueOf(orderNo), 1, 20).get(0).status()).isEqualTo(50);

        // 订单行被删（逻辑删除）→ 文档删除
        jdbc.update("UPDATE orders SET deleted = 1 WHERE order_no = ?", orderNo);
        indexer.sync(orderNo);
        refresh();
        assertThat(searchService.search(TENANT, String.valueOf(orderNo), 1, 20)).isEmpty();
    }

    @Test
    void searchFails_gracefullyWhenIndexMissing() throws Exception {
        es.indices().delete(d -> d.index(OrderSearchIndexer.INDEX).ignoreUnavailable(true));
        // ES 是查询副本：查不了就降级成明确的业务错误，不影响下单等交易链路
        assertThatThrownBy(() -> searchService.search(TENANT, "x", 1, 20)).isInstanceOf(BusinessException.class);
        // 索引 mapping 交还给 indexer 重建（它缓存了"已建好"标记，这里清掉让下一个用例重新建）
        org.springframework.test.util.ReflectionTestUtils.setField(indexer, "indexReady", false);
    }
}
