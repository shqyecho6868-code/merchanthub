package com.merchanthub.order.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowItem;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.merchanthub.order.client.ProductInventoryClient;
import com.merchanthub.order.ratelimit.OrderRateLimiter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * C2: load the config into Sentinel rules at startup.
 * <p>
 * Sentinel's rule managers are JVM-wide static singletons: rules pushed from the dashboard <b>replace wholesale</b> the same kind of rules loaded here, and a restart reverts to this config.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(SentinelProperties.class)
@RequiredArgsConstructor
public class SentinelRulesConfig {

    private final SentinelProperties props;

    /**
     * Clear before loading: the rule manager is a JVM-wide singleton shared by multiple test contexts in the same JVM;
     * if rule content is unchanged Sentinel reuses the old breakers / token buckets (including an "open" state), so clearing first gives a clean state
     */
    public void reload() {
        ParamFlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
        loadRules();
    }

    @PostConstruct
    public void loadRules() {
        ParamFlowRuleManager.loadRules(List.of(perTenantOrderRule(props.getRateLimit())));
        DegradeRuleManager.loadRules(degradeRules(props.getDegrade(),
                ProductInventoryClient.RES_QUERY_SKUS, ProductInventoryClient.RES_RESERVE));
        log.info("Sentinel 规则已加载：下单每商家 {} QPS，例外 {}；Feign 熔断 慢调用>{}ms 占比≥{} / 异常占比≥{}",
                props.getRateLimit().getPerTenantQps(), props.getRateLimit().getTenantOverrides(),
                props.getDegrade().getSlowRtMs(), props.getDegrade().getSlowRatio(), props.getDegrade().getErrorRatio());
    }

    /**
     * Hot-parameter rate limiting: for parameter 0 (tenantId) of resource order:create, <b>each distinct value gets its own token bucket</b>.
     * When tenant 7 uses up its 20 tokens only tenant 7 is rejected; tenant 8's bucket is unaffected -- that's "per-tenant isolation"
     */
    static ParamFlowRule perTenantOrderRule(SentinelProperties.RateLimit cfg) {
        List<ParamFlowItem> items = new ArrayList<>();
        cfg.getTenantOverrides().forEach((tenantId, qps) -> items.add(ParamFlowItem.newItem(tenantId, qps)));
        return new ParamFlowRule(OrderRateLimiter.RESOURCE)
                .setParamIdx(0)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(cfg.getPerTenantQps())
                .setDurationInSec(1)
                .setParamFlowItemList(items);
    }

    /** Two breaker rules per downstream resource: slow-call ratio + error ratio; either one trips the breaker */
    public static List<DegradeRule> degradeRules(SentinelProperties.Degrade cfg, String... resources) {
        List<DegradeRule> rules = new ArrayList<>();
        for (String res : resources) {
            rules.add(new DegradeRule(res)
                    .setGrade(RuleConstant.DEGRADE_GRADE_RT)
                    .setCount(cfg.getSlowRtMs())
                    .setSlowRatioThreshold(cfg.getSlowRatio())
                    .setMinRequestAmount(cfg.getMinRequests())
                    .setStatIntervalMs(cfg.getStatIntervalMs())
                    .setTimeWindow(cfg.getOpenSeconds()));
            rules.add(new DegradeRule(res)
                    .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                    .setCount(cfg.getErrorRatio())
                    .setMinRequestAmount(cfg.getMinRequests())
                    .setStatIntervalMs(cfg.getStatIntervalMs())
                    .setTimeWindow(cfg.getOpenSeconds()));
        }
        return rules;
    }
}
