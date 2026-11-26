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
 * C2 experiment (automated): when a big merchant's promotion saturates order placement, does it drag down small merchants?
 * <p>
 * Setup: per-merchant order threshold of 5 QPS (lowered for the test; production default is 20).
 * Tenant 7: 20 threads concurrently send 60 orders (far above 5 QPS); meanwhile tenant 8 places 1 order every 300ms, 3 in total (about 3 QPS, within its own threshold).
 * <p>
 * Conclusion (measured): only about 5-10 of tenant 7's orders succeed, the rest all get 429 + error code 42900 + Retry-After, and rejected requests write no orders;
 * tenant 8's 3 orders <b>all succeed</b>. Why: hot-parameter rate limiting gives each tenantId value its own token bucket, and tenant 7 only drains its own.
 * Contrast: with one global threshold for the whole order API (say 20 QPS), tenant 7's 60 requests would grab all 20 slots first and tenant 8 would be rejected too.
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
        return false;   // goes through MockMvc; identity comes from request headers
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
        // Tenant 8's buyer places orders normally during the spike
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

        // Tenant 7: most are rate-limited (not 500), and rejected requests wrote no orders
        assertThat(limited).as("租户 7 被限流的请求数").isGreaterThan(30);
        assertThat(passed + limited).isEqualTo(60);
        assertThat(ordersOf(BIG)).isEqualTo(passed);
        // Tenant 8: 100% pass rate
        for (MockHttpServletResponse r : small) {
            assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
            assertThat(r.getContentAsString()).contains("\"code\":200");
        }
        assertThat(ordersOf(SMALL)).isEqualTo(3);
    }

    @Test
    void tenantIdInRequestMustMatchSkuOwner() throws Exception {
        // Filling in another merchant's tenantId to use their quota: SKU 101 belongs to merchant 7, the request claims merchant 8 -> rejected, no order written
        MockHttpServletResponse r = placeOrder(SMALL, 101, 9100);
        assertThat(r.getContentAsString()).contains("\"code\":60007");
        assertThat(ordersOf(BIG) + ordersOf(SMALL)).isZero();
    }

    private long ordersOf(long tenantId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE tenant_id = ?", Long.class, tenantId);
        return n == null ? 0 : n;
    }
}
