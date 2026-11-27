package com.merchanthub.order.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring scheduling is enabled only for the outbox backlog monitor. Business compensation jobs stay on XXL-Job (see OrderJobs);
 * the monitor is the one thing that must not depend on XXL-Job, because it is what tells us XXL-Job isn't running.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "merchanthub.order.outbox.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
