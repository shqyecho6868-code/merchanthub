package com.merchanthub.order.client;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.order.config.SentinelProperties;
import com.merchanthub.order.config.SentinelRulesConfig;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What counts as a "downstream failure" that opens the breaker (no Spring, no middleware) */
class RemoteResultsTest {

    private static final String RES = "test:downstream";
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void rules() {
        SentinelProperties.Degrade cfg = new SentinelProperties.Degrade();
        cfg.setMinRequests(3);
        cfg.setOpenSeconds(30);
        DegradeRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(SentinelRulesConfig.degradeRules(cfg, RES));
    }

    @AfterEach
    void clear() {
        DegradeRuleManager.loadRules(List.of());
    }

    private int code(Supplier<BaseResponse<Object>> s) {
        try {
            RemoteResults.call(RES, () -> {
                calls.incrementAndGet();
                return s.get();
            });
            return 200;
        } catch (BusinessException e) {
            return e.getCode();
        }
    }

    @Test
    @DisplayName("A business failure (insufficient stock) means the downstream is healthy: never trips the breaker, error code passed through as-is")
    @SuppressWarnings("unchecked")
    void businessFailures_doNotOpenCircuit() {
        for (int i = 0; i < 10; i++) {
            assertThat(code(() -> (BaseResponse<Object>) ResultUtils.error(ErrorCode.INVENTORY_INSUFFICIENT)))
                    .isEqualTo(ErrorCode.INVENTORY_INSUFFICIENT.getCode());
        }
        assertThat(calls.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("Consecutive 5xx -> breaker opens; downstream no longer called, DOWNSTREAM_UNAVAILABLE directly")
    void serverErrors_openCircuit() {
        Request req = Request.create(Request.HttpMethod.POST, "/x", Map.of(), null, StandardCharsets.UTF_8, null);
        for (int i = 0; i < 3; i++) {
            assertThat(code(() -> {
                throw new FeignException.InternalServerError("boom", req, null, Map.of());
            })).isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        }
        assertThat(code(() -> ResultUtils.success(null))).isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        assertThat(calls.get()).as("the 4th call is blocked by the breaker, no real call made").isEqualTo(3);
    }

    @Test
    @DisplayName("4xx means our own request is wrong; not counted toward the breaker")
    void clientErrors_doNotOpenCircuit() {
        Request req = Request.create(Request.HttpMethod.POST, "/x", Map.of(), null, StandardCharsets.UTF_8, null);
        for (int i = 0; i < 5; i++) {
            code(() -> {
                throw new FeignException.BadRequest("bad", req, null, Map.of());
            });
        }
        assertThat(code(() -> ResultUtils.success(null))).isEqualTo(200);
    }
}
