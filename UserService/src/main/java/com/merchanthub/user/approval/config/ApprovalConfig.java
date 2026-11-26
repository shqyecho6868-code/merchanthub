package com.merchanthub.user.approval.config;

import com.merchanthub.user.approval.mq.ListingResultEvent;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ApprovalConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Initialize the Flowable engine <b>after</b> Flyway (by taking FlywayMigrationInitializer as a parameter, Spring builds it first).
     * <p>
     * Why ordering matters: Spring Boot only guarantees JPA / JdbcTemplate etc. run after Flyway, not Flowable.
     * On a brand-new empty DB, if Flowable runs first, the DB already has a pile of ACT_* tables but no flyway_schema_history,
     * and Flyway refuses to migrate with "Found non-empty schema without schema history table" (baseline-on-migrate is worse: it silently skips V1 as already applied).
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> flowableAfterFlyway(FlywayMigrationInitializer flywayInitializer) {
        // Don't generate process diagram PNGs on deploy: no page displays them (a process designer is out of scope), and generating them needs AWT + system fonts,
        // which on Linux without CJK fonts (CI / containers) either errors out or renders boxes
        return cfg -> cfg.setCreateDiagramOnDeploy(false);
    }

    /** A plain producer is enough: consistency comes from the local outbox table, MQ transactions aren't needed (same as order) */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer approvalOutboxProducer(@Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr) {
        DefaultMQProducer producer = new DefaultMQProducer("approval-outbox-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(3000);
        producer.setTopics(List.of(ListingResultEvent.TOPIC));   // prefetch routes on startup (P18)
        return producer;
    }

    /** Thread pool for the "send once right after commit" path; drop when the queue is full: the record is still in the table and the scan will resend, so approval requests must never be held up */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService approvalOutboxSendExecutor() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000),
                r -> new Thread(r, "approval-outbox-send-" + n.incrementAndGet()), new ThreadPoolExecutor.DiscardPolicy());
    }
}
