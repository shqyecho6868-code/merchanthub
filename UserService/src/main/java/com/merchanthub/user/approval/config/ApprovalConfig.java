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
     * 让 Flowable 引擎在 Flyway <b>之后</b>初始化（参数里要了 FlywayMigrationInitializer，Spring 就会先把它建好）。
     * <p>
     * 为什么要管顺序：Spring Boot 只保证 JPA / JdbcTemplate 等在 Flyway 之后，不管 Flowable。
     * 全新空库上如果 Flowable 先跑，库里已经有一堆 ACT_* 表、却没有 flyway_schema_history，
     * Flyway 会报 "Found non-empty schema without schema history table" 拒绝迁移（开了 baseline-on-migrate 更糟：直接把 V1 当成已执行跳过）。
     */
    @Bean
    public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> flowableAfterFlyway(FlywayMigrationInitializer flywayInitializer) {
        // 部署时不生成流程图 PNG：没有页面要展示它（流程设计器范围外），而生成要用 AWT + 系统字体，
        // 在没装中文字体的 Linux（CI / 容器）上要么报错要么方块字
        return cfg -> cfg.setCreateDiagramOnDeploy(false);
    }

    /** 普通生产者即可：一致性靠本地消息表，不需要 MQ 的事务能力（同 order） */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer approvalOutboxProducer(@Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr) {
        DefaultMQProducer producer = new DefaultMQProducer("approval-outbox-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(3000);
        producer.setTopics(List.of(ListingResultEvent.TOPIC));   // 启动时预取路由（P18）
        return producer;
    }

    /** 提交后"立即发一次"的线程池；队列满直接丢：记录还在表里，扫描会补发，不能拖住审批请求 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService approvalOutboxSendExecutor() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(1, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000),
                r -> new Thread(r, "approval-outbox-send-" + n.incrementAndGet()), new ThreadPoolExecutor.DiscardPolicy());
    }
}
