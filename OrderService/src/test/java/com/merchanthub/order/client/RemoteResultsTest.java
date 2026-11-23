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

/** C2：什么算"下游故障"、会让熔断打开（不起 Spring、不连中间件） */
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
    @DisplayName("业务失败（库存不足）说明下游健康：再多也不熔断，错误码原样透传")
    @SuppressWarnings("unchecked")
    void businessFailures_doNotOpenCircuit() {
        for (int i = 0; i < 10; i++) {
            assertThat(code(() -> (BaseResponse<Object>) ResultUtils.error(ErrorCode.INVENTORY_INSUFFICIENT)))
                    .isEqualTo(ErrorCode.INVENTORY_INSUFFICIENT.getCode());
        }
        assertThat(calls.get()).isEqualTo(10);
    }

    @Test
    @DisplayName("连续 5xx → 熔断，之后不再调用下游，直接 DOWNSTREAM_UNAVAILABLE")
    void serverErrors_openCircuit() {
        Request req = Request.create(Request.HttpMethod.POST, "/x", Map.of(), null, StandardCharsets.UTF_8, null);
        for (int i = 0; i < 3; i++) {
            assertThat(code(() -> {
                throw new FeignException.InternalServerError("boom", req, null, Map.of());
            })).isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        }
        assertThat(code(() -> ResultUtils.success(null))).isEqualTo(ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
        assertThat(calls.get()).as("第 4 次被熔断挡住，没有真正调用").isEqualTo(3);
    }

    @Test
    @DisplayName("4xx 是我们自己的请求有问题，不计入熔断")
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
