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
 * C2：下单入口按商家限流（Sentinel 热点参数，规则见 SentinelRulesConfig）。
 * <p>
 * 放在 Controller 调 service 之前：被限的请求<b>不查 SKU、不预占、不碰数据库</b>，拒绝成本几乎为 0。
 * 不用 @SentinelResource 注解：注解按"方法参数下标"取热点参数，Controller 的参数是整个请求体，
 * 拿不到 tenantId；手写 SphU.entry 把 tenantId 显式传进去更直观。
 */
@Slf4j
@Component
public class OrderRateLimiter {

    /** Sentinel 资源名，控制台"热点规则"里按这个名字配置 */
    public static final String RESOURCE = "order:create";

    public <T> T guard(long tenantId, Supplier<T> action) {
        Entry entry;
        try {
            // 第 4 个参数开始是热点参数：按 tenantId 的值分别计数
            entry = SphU.entry(RESOURCE, EntryType.IN, 1, tenantId);
        } catch (BlockException e) {
            log.warn("【限流】商家下单 QPS 超过阈值，拒绝 tenantId={}", tenantId);
            throw new TooManyRequestsException(ErrorCode.TOO_MANY_REQUESTS.getMessage());
        }
        try {
            return action.get();
        } finally {
            // exit 时也要带上同样的参数，热点规则按"并发线程数"计时才能正确减回去
            entry.exit(1, tenantId);
        }
    }
}
