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
 * C2：启动时把配置加载成 Sentinel 规则。
 * <p>
 * Sentinel 的规则管理器是 JVM 级的静态单例：控制台推下来的规则会<b>整体替换</b>这里加载的同类规则，重启后又回到这里的配置。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(SentinelProperties.class)
@RequiredArgsConstructor
public class SentinelRulesConfig {

    private final SentinelProperties props;

    /**
     * 先清空再加载：规则管理器是 JVM 级单例，同一 JVM 里的多个测试上下文共用它；
     * 规则内容不变时 Sentinel 会沿用旧的熔断器 / 令牌桶（连同"已熔断"状态），先清空才能得到干净的状态
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
     * 热点参数限流：资源 order:create 的第 0 个参数（tenantId）<b>每个不同的值各有一个令牌桶</b>。
     * 租户 7 把自己的 20 个令牌用完只会拒绝租户 7，租户 8 的桶不受影响 —— 这就是"按租户隔离"
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

    /** 每个下游资源两条熔断规则：慢调用比例 + 异常比例，任一触发就熔断 */
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
