package com.merchanthub.order.ratelimit;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.exception.TooManyRequestsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * C2: per-merchant rate limiting at the order-placement entry (Sentinel hot parameters; rules in SentinelRulesConfig).
 * <p>
 * Placed before the Controller calls the service: throttled requests <b>don't fetch SKUs, reserve, or touch the DB</b>, so rejection costs almost nothing.
 * No @SentinelResource annotation: it picks hot parameters by method-argument index, and the Controller's argument is the whole request body,
 * so it can't get tenantId; passing tenantId explicitly to a hand-written SphU.entry is clearer.
 */
@Slf4j
@Component
public class OrderRateLimiter {

    /** Sentinel resource name; configure "hot parameter rules" in the dashboard under this name */
    public static final String RESOURCE = "order:create";

    public <T> T guard(long tenantId, Supplier<T> action) {
        Entry entry;
        try {
            // Arguments from the 4th onward are hot parameters: counted separately per tenantId value
            entry = SphU.entry(RESOURCE, EntryType.IN, 1, tenantId);
        } catch (BlockException e) {
            log.warn("【限流】商家下单 QPS 超过阈值，拒绝 tenantId={}", tenantId);
            throw new TooManyRequestsException(ErrorCode.TOO_MANY_REQUESTS.getMessage());
        }
        try {
            return action.get();
        } finally {
            // exit must pass the same arguments so hot-parameter rules counting "concurrent threads" decrement correctly
            entry.exit(1, tenantId);
        }
    }
}
