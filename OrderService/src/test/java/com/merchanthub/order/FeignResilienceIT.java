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
 * C2: a real Feign client (not mocked) calls a "very slow fake product-inventory" to verify two things:
 * <ol>
 *   <li>No <b>automatic retry</b> after a read timeout: the fake service is hit only once</li>
 *   <li><b>Circuit breaking</b> after consecutive timeouts: later calls don't even send a request and degrade to DOWNSTREAM_UNAVAILABLE within milliseconds</li>
 * </ol>
 * Thresholds are lowered for speed: read timeout 300ms, slow call 200ms, judge after 3 calls in the window, break for 30s.
 */
@SpringBootTest(properties = {
        "spring.cloud.openfeign.client.config.productInventoryClient.read-timeout=300",
        "merchanthub.order.sentinel.degrade.slow-rt-ms=200",
        "merchanthub.order.sentinel.degrade.min-requests=3",
        "merchanthub.order.sentinel.degrade.open-seconds=30",
})
class FeignResilienceIT extends IntegrationTestBase {

    /** Fake product-inventory: each request sleeps 1s (longer than the read timeout) before returning success */
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
                // The caller already disconnected after its timeout, so failing to write back is expected
            }
            ex.close();
        });
        SLOW_INVENTORY.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        SLOW_INVENTORY.start();
    }

    /** Tests don't connect to Nacos; Spring Cloud's built-in "static service list" points product-inventory at the fake service */
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
        Thread.sleep(800);   // leave time for any "possible retry"
        assertThat(HITS.get()).as("should not retry automatically after timeout").isEqualTo(1);
    }

    @Test
    void consecutiveTimeouts_openCircuit_thenFailFastWithoutCalling() {
        for (int i = 0; i < 3; i++) {
            assertDownstreamUnavailable();
        }
        int hitsBeforeOpen = HITS.get();
        long elapsedMs = timedUnavailableCall();

        // G-上 CI flake (P47): Sentinel breaker stats use "a single bucket aligned to whole 10s" (LeapArray(1, statIntervalMs)),
        // so when 3 calls straddle a 10s boundary they split into two windows, min-requests=3 isn't met, and the breaker isn't open yet.
        // In that case this call still hit the downstream; make up to 3 more calls until one doesn't hit the downstream (= breaker open)
        for (int extra = 0; extra < 3 && HITS.get() != hitsBeforeOpen; extra++) {
            hitsBeforeOpen = HITS.get();
            elapsedMs = timedUnavailableCall();
        }

        assertThat(HITS.get()).as("no more requests after breaker opens").isEqualTo(hitsBeforeOpen);
        assertThat(elapsedMs).as("fail fast after breaker opens without waiting for the 300ms timeout").isLessThan(100);
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
