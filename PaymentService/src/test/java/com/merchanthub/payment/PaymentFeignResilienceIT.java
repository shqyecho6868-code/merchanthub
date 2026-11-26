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
 * E3 (C2 follow-up): payment → order real Feign calls hit a "very slow fake order":
 * no retry after a read timeout; after consecutive timeouts the circuit opens, and subsequent calls send no request and degrade to DOWNSTREAM_UNAVAILABLE within a few ms.
 * Business failures (order not found, HTTP 200 + code 40400) don't count toward the circuit breaker and are passed through as-is.
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
        // Order number 1: very slow (exceeds the read timeout); order number 2: immediately returns "order not found"
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
                // The caller has already disconnected after timing out
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
        long elapsedMs = timedCall();

        // Same as order's FeignResilienceIT (P47): Sentinel's circuit-breaker stats align to whole 10-second windows; when the 3 calls straddle a window boundary
        // they don't reach min-requests and the breaker hasn't opened yet (reproduced on this payment copy in G-下 CI, P51).
        // If this call still reached the downstream, make up to 3 more calls until one doesn't (= the breaker is open)
        for (int extra = 0; extra < 3 && HITS.get() != hits; extra++) {
            hits = HITS.get();
            elapsedMs = timedCall();
        }
        assertThat(elapsedMs).as("熔断后快速失败").isLessThan(100);
        assertThat(HITS.get()).as("熔断后不再发请求").isEqualTo(hits);
    }

    private long timedCall() {
        long start = System.nanoTime();
        assertCode(1, ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        return (System.nanoTime() - start) / 1_000_000;
    }

    @Test
    void businessFailure_passedThrough_notCountedAsFault() {
        for (int i = 0; i < 5; i++) {
            assertCode(2, 40400);
        }
        assertThat(HITS.get()).as("业务失败不触发熔断，每次都真的发了请求").isEqualTo(5);
    }
}
