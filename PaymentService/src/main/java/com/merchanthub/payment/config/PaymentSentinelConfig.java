package com.merchanthub.payment.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.merchanthub.payment.client.OrderClient;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * E3（C2 遗留）：payment 调 order 的熔断规则。每个资源两条：慢调用比例 + 异常比例，任一触发就熔断。
 * 阈值含义同 order（C2 笔记 Q3）；规则只在内存里，生产应放 Nacos 持久化。
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PaymentSentinelConfig.Degrade.class)
@RequiredArgsConstructor
public class PaymentSentinelConfig {

    private final Degrade cfg;

    @Data
    @ConfigurationProperties(prefix = "merchanthub.payment.sentinel.degrade")
    public static class Degrade {
        private int slowRtMs = 1000;
        private double slowRatio = 0.5;
        private double errorRatio = 0.5;
        private int minRequests = 10;
        private int statIntervalMs = 10_000;
        private int openSeconds = 10;
    }

    /** 先清空再加载：规则管理器是 JVM 级单例，测试里多个上下文共用，清空才能拿到干净的熔断器状态 */
    @PostConstruct
    public void reload() {
        DegradeRuleManager.loadRules(List.of());
        List<DegradeRule> rules = new ArrayList<>();
        for (String res : List.of(OrderClient.RES_GET_ORDER, OrderClient.RES_PAY_CHECK, OrderClient.RES_STATUSES)) {
            rules.add(new DegradeRule(res).setGrade(RuleConstant.DEGRADE_GRADE_RT).setCount(cfg.getSlowRtMs())
                    .setSlowRatioThreshold(cfg.getSlowRatio()).setMinRequestAmount(cfg.getMinRequests())
                    .setStatIntervalMs(cfg.getStatIntervalMs()).setTimeWindow(cfg.getOpenSeconds()));
            rules.add(new DegradeRule(res).setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO).setCount(cfg.getErrorRatio())
                    .setMinRequestAmount(cfg.getMinRequests())
                    .setStatIntervalMs(cfg.getStatIntervalMs()).setTimeWindow(cfg.getOpenSeconds()));
        }
        DegradeRuleManager.loadRules(rules);
        log.info("Sentinel 熔断规则已加载：payment → order，慢调用>{}ms 占比≥{} / 异常占比≥{}，熔断 {} 秒",
                cfg.getSlowRtMs(), cfg.getSlowRatio(), cfg.getErrorRatio(), cfg.getOpenSeconds());
    }
}
