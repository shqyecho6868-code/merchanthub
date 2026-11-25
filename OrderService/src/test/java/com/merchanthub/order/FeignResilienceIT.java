package com.merchanthub.order;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.order.client.ProductInventoryClient;
import com.merchanthub.order.client.RemoteResults;
import com.merchanthub.order.client.dto.ReserveRequest;
import com.merchanthub.order.config.SentinelRulesConfig;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.testsupport.IntegrationTestBase;
import com.merchanthub.testsupport.SharedContainers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C2：真实 Feign 客户端（不 mock）打到一个"很慢的假 product-inventory"上，验证两件事：
 * <ol>
 *   <li>读超时后<b>不自动重试</b>：假服务只被打到 1 次</li>
 *   <li>连续超时后<b>熔断</b>：后面的调用连请求都不发，几毫秒内直接降级成 DOWNSTREAM_UNAVAILABLE</li>
 * </ol>
 * 为了测试快，阈值调小：读超时 300ms、慢调用 200ms、统计窗口内 3 次就判断、熔断 30 秒。
 */
@SpringBootTest(properties = {
        "spring.cloud.openfeign.client.config.productInventoryClient.read-timeout=300",
        "merchanthub.order.sentinel.degrade.slow-rt-ms=200",
        "merchanthub.order.sentinel.degrade.min-requests=3",
        "merchanthub.order.sentinel.degrade.open-seconds=30",
})
class FeignResilienceIT extends IntegrationTestBase {

    /** 假 product-inventory：每个请求睡 1 秒（超过读超时）再返回成功 */
    private static final HttpServer SLOW_INVENTORY;
    private static final AtomicInteger HITS = new AtomicInteger();

    static {
        try {
            SLOW_INVENTORY = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SLOW_INVENTORY.createContext("/internal/inventory/reserve", ex -> {
            HITS.incrementAndGet();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"code\":200,\"message\":\"ok\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            try {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } catch (IOException ignored) {
                // 调用方超时后已经断开，写不回去是预期内的
            }
            ex.close();
        });
        SLOW_INVENTORY.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        SLOW_INVENTORY.start();
    }

    /** 测试不连 Nacos，用 Spring Cloud 自带的"静态服务列表"把 product-inventory 指到假服务 */
    @DynamicPropertySource
    static void inventoryAddress(DynamicPropertyRegistry r) {
        r.add("spring.cloud.discovery.client.simple.instances.product-inventory[0].uri",
                () -> "http://127.0.0.1:" + SLOW_INVENTORY.getAddress().getPort());
    }

    @Autowired
    private ProductInventoryClient client;

    @Autowired
    private SentinelRulesConfig sentinelRules;

    @BeforeAll
    static void createTopics() {
        SharedContainers.rocketmq().createTopic(OrderEventTopics.TOPIC);
        SharedContainers.rocketmq().createTopic(OrderEventTopics.TIMEOUT_TOPIC);
    }

    @AfterAll
    static void stopServer() {
        SLOW_INVENTORY.stop(0);
    }

    @BeforeEach
    void reset() {
        sentinelRules.reload();
        HITS.set(0);
    }

    private void reserveOnce() {
        RemoteResults.call(ProductInventoryClient.RES_RESERVE, () -> client.reserve(
                new ReserveRequest(1L, 7L, List.of(new ReserveRequest.Item(101L, 1)))));
    }

    @Test
    void readTimeout_notRetried() throws Exception {
        assertDownstreamUnavailable();
        Thread.sleep(800);   // 给"可能的重试"留出时间
        assertThat(HITS.get()).as("超时后不应自动重试").isEqualTo(1);
    }

    @Test
    void consecutiveTimeouts_openCircuit_thenFailFastWithoutCalling() {
        for (int i = 0; i < 3; i++) {
            assertDownstreamUnavailable();
        }
        int hitsBeforeOpen = HITS.get();
        long elapsedMs = timedUnavailableCall();

        // G-上 CI 偶发（P47）：Sentinel 熔断统计是"按整 10 秒对齐的单个桶"（LeapArray(1, statIntervalMs)），
        // 3 次调用恰好跨过 10 秒边界时被拆进两个窗口，凑不够 min-requests=3，熔断器还没打开。
        // 这时这次调用仍然打到了下游；最多再补 3 次，直到某次调用没打到下游（= 熔断已打开）
        for (int extra = 0; extra < 3 && HITS.get() != hitsBeforeOpen; extra++) {
            hitsBeforeOpen = HITS.get();
            elapsedMs = timedUnavailableCall();
        }

        assertThat(HITS.get()).as("熔断后不再发请求").isEqualTo(hitsBeforeOpen);
        assertThat(elapsedMs).as("熔断后快速失败，不用等 300ms 超时").isLessThan(100);
    }

    private long timedUnavailableCall() {
        long start = System.nanoTime();
        assertDownstreamUnavailable();
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void assertDownstreamUnavailable() {
        assertThatThrownBy(this::reserveOnce).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
    }
}
