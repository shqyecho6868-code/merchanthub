package com.merchanthub.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * C2 实验（自动化版）：大商家搞活动把下单打满，会不会连累小商家？
 * <p>
 * 设置：每个商家下单阈值 5 QPS（测试里调小，生产默认 20）。
 * 租户 7：20 个线程并发发 60 单（远超 5 QPS）；同时租户 8 每 300ms 下 1 单，共 3 单（约 3 QPS，在自己的阈值内）。
 * <p>
 * 结论（实测）：租户 7 只有约 5～10 单成功，其余全部 429 + 错误码 42900 + Retry-After，且被拒的请求没写任何订单；
 * 租户 8 的 3 单<b>全部成功</b>。原因：热点参数限流给每个 tenantId 值各开一个令牌桶，租户 7 用光的是自己的桶。
 * 对照：如果给整个下单接口设一个总阈值（比如 20 QPS），租户 7 的 60 个请求会先把 20 个名额抢光，租户 8 一样被拒。
 */
@SpringBootTest(properties = "merchanthub.order.sentinel.rate-limit.per-tenant-qps=5")
@AutoConfigureMockMvc
class TenantRateLimitIT extends OrderItSupport {

    private static final long BIG = TENANT;     // 7
    private static final long SMALL = 8L;

    @Autowired
    private MockMvc mvc;

    @Override
    protected boolean defaultSystemContext() {
        return false;   // 走 MockMvc，身份由请求头决定
    }

    private MockHttpServletResponse placeOrder(long tenantId, long skuId, long buyer) throws Exception {
        String body = "{\"tenantId\":" + tenantId + ",\"items\":[{\"skuId\":" + skuId + ",\"quantity\":1}]}";
        return mvc.perform(post("/orders").header("X-User-Id", buyer).header("X-Role", "BUYER")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    @Test
    void bigTenantFlooding_isLimited_smallTenantUnaffected() throws Exception {
        catalog.put(201L, sku(201, SMALL, 500, "{}"));

        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<MockHttpServletResponse>> flood = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            long buyer = 5000 + i;
            flood.add(pool.submit(() -> placeOrder(BIG, 101, buyer)));
        }
        // 租户 8 的买家在洪峰期间正常下单
        List<MockHttpServletResponse> small = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            small.add(placeOrder(SMALL, 201, 9000 + i));
            Thread.sleep(300);
        }

        int passed = 0;
        int limited = 0;
        for (Future<MockHttpServletResponse> f : flood) {
            MockHttpServletResponse r = f.get();
            if (r.getStatus() == 429) {
                limited++;
                assertThat(r.getContentAsString()).contains("\"code\":42900");
                assertThat(r.getHeader("Retry-After")).isEqualTo("1");
            } else {
                assertThat(r.getStatus()).isEqualTo(200);
                assertThat(r.getContentAsString()).contains("\"code\":200");
                passed++;
            }
        }
        pool.shutdown();

        // 租户 7：大部分被限流（不是 500），且被拒的请求没有写订单
        assertThat(limited).as("租户 7 被限流的请求数").isGreaterThan(30);
        assertThat(passed + limited).isEqualTo(60);
        assertThat(ordersOf(BIG)).isEqualTo(passed);
        // 租户 8：通过率 100%
        for (MockHttpServletResponse r : small) {
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
            assertThat(r.getContentAsString()).contains("\"code\":200");
        }
        assertThat(ordersOf(SMALL)).isEqualTo(3);
    }

    @Test
    void tenantIdInRequestMustMatchSkuOwner() throws Exception {
        // 填别家的 tenantId 想用别人的配额：SKU 101 属于商家 7，请求说是商家 8 → 拒绝，不落单
        MockHttpServletResponse r = placeOrder(SMALL, 101, 9100);
        assertThat(r.getContentAsString()).contains("\"code\":60007");
        assertThat(ordersOf(BIG) + ordersOf(SMALL)).isZero();
    }

    private long ordersOf(long tenantId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ?", Long.class, tenantId);
        return n == null ? 0 : n;
    }
}
