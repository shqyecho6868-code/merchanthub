package com.merchanthub.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * C2：限流 / 熔断阈值。启动时由 {@link SentinelRulesConfig} 加载成 Sentinel 规则（只在内存里）。
 * 生产要把规则放到 Nacos 配置中心持久化 + 动态推送，否则控制台改的规则重启就丢（C2 范围外，见 C2 笔记）。
 */
@Data
@ConfigurationProperties(prefix = "merchanthub.order.sentinel")
public class SentinelProperties {

    private RateLimit rateLimit = new RateLimit();
    private Degrade degrade = new Degrade();

    @Data
    public static class RateLimit {
        /** 每个商家的下单 QPS 默认阈值（单机）。取法：单机容量 100 ÷ 预期同时大促的商家数 5 ≈ 20，见 C2 笔记 Q2 */
        private int perTenantQps = 20;
        /** 例外项：个别商家单独的阈值（付费套餐 / 报备过的大促），key = tenantId */
        private Map<Long, Integer> tenantOverrides = new HashMap<>();
    }

    @Data
    public static class Degrade {
        /** 慢调用：响应超过这么多毫秒算"慢" */
        private int slowRtMs = 1000;
        /** 统计窗口内慢调用占比 ≥ 它就熔断 */
        private double slowRatio = 0.5;
        /** 统计窗口内异常（超时 / 连不上 / 5xx）占比 ≥ 它就熔断 */
        private double errorRatio = 0.5;
        /** 统计窗口内至少这么多次调用才判断（防止 2 次里错 1 次就熔断） */
        private int minRequests = 10;
        private int statIntervalMs = 10_000;
        /** 熔断后多少秒内直接快速失败；到时放一个探测请求，成功就恢复 */
        private int openSeconds = 10;
    }
}
