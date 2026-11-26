package com.merchanthub.order.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Rate-limit / circuit-breaker thresholds. Loaded into Sentinel rules (in memory only) by {@link SentinelRulesConfig} at startup.
 * In production the rules should be persisted in the Nacos config center + pushed dynamically; otherwise rules changed in the dashboard are lost on restart (out of scope for this demo).
 */
@Data
@ConfigurationProperties(prefix = "merchanthub.order.sentinel")
public class SentinelProperties {

    private RateLimit rateLimit = new RateLimit();
    private Degrade degrade = new Degrade();

    @Data
    public static class RateLimit {
        /** Default per-merchant order-placement QPS threshold (per instance). Derivation: per-instance capacity 100 / ~5 merchants expected to run promotions at once = 20 */
        private int perTenantQps = 20;
        /** Exceptions: per-merchant thresholds (paid plans / pre-registered promotions), key = tenantId */
        private Map<Long, Integer> tenantOverrides = new HashMap<>();
    }

    @Data
    public static class Degrade {
        /** Slow call: a response taking longer than this many ms counts as "slow" */
        private int slowRtMs = 1000;
        /** Break when the slow-call ratio in the stats window >= this */
        private double slowRatio = 0.5;
        /** Break when the error ratio (timeout / connection failure / 5xx) in the stats window >= this */
        private double errorRatio = 0.5;
        /** Minimum calls in the stats window before judging (prevents breaking on 1 failure out of 2) */
        private int minRequests = 10;
        private int statIntervalMs = 10_000;
        /** Seconds to fail fast after breaking; then let one probe request through, and recover if it succeeds */
        private int openSeconds = 10;
    }
}
