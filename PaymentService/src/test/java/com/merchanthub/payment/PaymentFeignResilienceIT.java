package com.merchanthub.payment;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.payment.config.PaymentSentinelConfig;
import com.merchanthub.payment.service.PaymentService;
import com.merchanthub.testsupport.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E3（补 C2 遗留）：payment → order 的真实 Feign 调用打到一个"很慢的假 order"上：
 * 读超时后不重试；连续超时后熔断，之后的调用不发请求、几毫秒内降级成 DOWNSTREAM_UNAVAILABLE。
 * 业务失败（订单不存在，HTTP 200 + code 40400）不计入熔断，原样透传。
 */
@SpringBootTest(properties = {
        "spring.cloud.openfeign.client.config.paymentOrderClient.read-timeout=300",
        "merchanthub.payment.sentinel.degrade.slow-rt-ms=200",
        "merchanthub.payment.sentinel.degrade.min-requests=3",
        "merchanthub.payment.sentinel.degrade.open-seconds=30",
})
class PaymentFeignResilienceIT extends IntegrationTestBase {

    private static final HttpServer FAKE_ORDER;
    private static final AtomicInteger HITS = new AtomicInteger();

    static {
        try {
            FAKE_ORDER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // 订单号 1：很慢（超过读超时）；订单号 2：立即返回"订单不存在"
        FAKE_ORDER.createContext("/internal/orders/", ex -> {
            HITS.incrementAndGet();
            boolean slow = ex.getRequestURI().getPath().endsWith("/1");
            if (slow) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = (slow ? "{\"code\":200,\"message\":\"ok\",\"data\":null}"
                    : "{\"code\":40400,\"message\":\"订单不存在\",\"data\":null}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } catch (IOException ignored) {
                // 调用方超时后已断开
            }
            ex.close();
        });
        FAKE_ORDER.setExecutor(Executors.newCachedThreadPool());
        FAKE_ORDER.start();
    }

    @DynamicPropertySource
    static void orderAddress(DynamicPropertyRegistry r) {
        r.add("spring.cloud.discovery.client.simple.instances.order[0].uri",
                () -> "http://127.0.0.1:" + FAKE_ORDER.getAddress().getPort());
    }

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentSentinelConfig sentinel;

    @AfterAll
    static void stop() {
        FAKE_ORDER.stop(0);
    }

    @BeforeEach
    void reset() {
        sentinel.reload();
        HITS.set(0);
    }

    private void create(long orderNo) {
        TenantContext.runAs(TenantInfo.system(), () -> paymentService.create(1001L, orderNo));
    }

    private void assertCode(long orderNo, int code) {
        assertThatThrownBy(() -> create(orderNo)).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(code);
    }

    @Test
    void consecutiveTimeouts_openCircuit_thenFailFast() throws Exception {
        assertCode(1, ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        Thread.sleep(800);
        assertThat(HITS.get()).as("超时后不自动重试").isEqualTo(1);
        assertCode(1, ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        assertCode(1, ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        int hits = HITS.get();

        long start = System.nanoTime();
        assertCode(1, ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        assertThat((System.nanoTime() - start) / 1_000_000).as("熔断后快速失败").isLessThan(100);
        assertThat(HITS.get()).as("熔断后不再发请求").isEqualTo(hits);
    }

    @Test
    void businessFailure_passedThrough_notCountedAsFault() {
        for (int i = 0; i < 5; i++) {
            assertCode(2, 40400);
        }
        assertThat(HITS.get()).as("业务失败不触发熔断，每次都真的发了请求").isEqualTo(5);
    }
}
