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
 * Circuit-breaker rules for payment calling order. Two rules per resource: slow-call ratio + exception ratio; either one trips the breaker.
 * Thresholds mean the same as in order; rules live only in memory — in production they should be persisted in Nacos.
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

    /** Clear before loading: the rule manager is a JVM-wide singleton shared by multiple test contexts; clearing is the only way to get clean breaker state */
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
        log.info("Sentinel circuit-breaker rules loaded: payment → order, slow call >{}ms ratio ≥{} / exception ratio ≥{}, open for {} s",
                cfg.getSlowRtMs(), cfg.getSlowRatio(), cfg.getErrorRatio(), cfg.getOpenSeconds());
    }
}
