package com.merchanthub.payment.client;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.merchanthub.common.common.BaseResponse;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.BusinessException;
import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * Wraps payment → order calls in a Sentinel circuit breaker (E3, C2 follow-up). Same approach as order's RemoteResults:
 * <ul>
 *   <li>Circuit open: no request is sent at all, DOWNSTREAM_UNAVAILABLE is thrown immediately — payment threads don't pile up on 3-second timeouts one by one</li>
 *   <li>Only timeouts / connection failures / 5xx count toward breaker stats; 4xx and business failures (order not found) show the downstream is healthy and don't count</li>
 *   <li>Business failures pass the downstream error code through as-is</li>
 * </ul>
 * See {@link com.merchanthub.payment.config.PaymentSentinelConfig} for the breaker rules.
 */
@Slf4j
public final class OrderRemote {

    private static final int SUCCESS = 200;

    private OrderRemote() {
    }

    public static <T> T call(String resource, Supplier<BaseResponse<T>> call) {
        Entry entry;
        try {
            entry = SphU.entry(resource, EntryType.OUT);
        } catch (BlockException e) {
            log.warn("【熔断】{} 熔断中，快速失败", resource);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        BaseResponse<T> resp;
        try {
            resp = call.get();
        } catch (FeignException e) {
            // RetryableException (timeout / connection failure) has status -1 and also lands here
            if (e.status() < 0 || e.status() >= 500) {
                Tracer.traceEntry(e, entry);
            }
            log.warn("调用 order 失败: {} status={}", resource, e.status(), e);
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        } finally {
            entry.exit();
        }
        if (resp == null) {
            throw new BusinessException(ErrorCode.DOWNSTREAM_UNAVAILABLE);
        }
        if (resp.getCode() != SUCCESS) {
            throw new BusinessException(resp.getCode(), resp.getMessage());
        }
        return resp.getData();
    }
}
