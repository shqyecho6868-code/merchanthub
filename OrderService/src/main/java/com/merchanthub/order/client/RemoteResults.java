package com.merchanthub.order.client;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Unified handling of remote call results:
 * - Business failure (body.code != 200): pass through the downstream error code and message as-is, e.g. "insufficient stock"
 * - Timeout / connection failure / 5xx: convert to DOWNSTREAM_UNAVAILABLE (degrade: fail fast with a clear message instead of dumping stack traces)
 * <p>
 * C2: each call is wrapped in a Sentinel resource (names in ProductInventoryClient.RES_*); circuit-breaker rules in SentinelRulesConfig.
 * Downstream keeps being slow / failing -> breaker opens, and for the next N seconds <b>no requests are sent</b>; we degrade immediately.
 * Benefit: order threads don't pile up on 3s timeouts one by one (otherwise a slow downstream drags order down too), and the downstream gets breathing room to recover.
 * <p>
 * We don't use Spring Cloud Alibaba's feign.sentinel.enabled auto-integration: it routes <b>all</b> exceptions (including decode exceptions from
 * business failures like "insufficient stock") to the fallback, and resource names are long strings like "POST:http://product-inventory/internal/...";
 * a hand-written layer controls precisely "what counts as a downstream failure".
 */
@Slf4j
public final class RemoteResults {

    private static final int SUCCESS = 200;

    private RemoteResults() {
    }

    public static <T> T call(String resource, Supplier<BaseResponse<T>> call) {
        Entry entry;
        try {
            entry = SphU.entry(resource, EntryType.OUT);
        } catch (BlockException e) {
            // Breaker open: don't even send the request, degrade immediately
            log.warn("【熔断】{} 熔断中，快速失败", resource);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        BaseResponse<T> resp;
        try {
            resp = call.get();
        } catch (RetryableException e) {
            // Timeout or connection failure: we don't know whether the other side executed it (see "known gap" in B1 notes section 2)
            Tracer.traceEntry(e, entry);
            log.warn("远程调用超时或连接失败: {}", resource, e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        } catch (FeignException e) {
            // Only 5xx / no status code counts as a "downstream failure" for breaker stats; 4xx means our own request is wrong, and breaking the downstream won't help
            if (e.status() < 0 || e.status() >= 500) {
                Tracer.traceEntry(e, entry);
            }
            log.warn("远程调用异常: {} status={}", resource, e.status(), e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        } finally {
            // On exit Sentinel records this call's latency; the slow-call-ratio breaker relies on it
            entry.exit();
        }
        if (resp == null) {
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (resp.getCode() != SUCCESS) {
            // A business failure (insufficient stock, etc.) means the downstream is healthy; don't count it toward breaker stats
            throw new BusinessException(resp.getCode(), resp.getMessage());
        }
        return resp.getData();
    }
}
