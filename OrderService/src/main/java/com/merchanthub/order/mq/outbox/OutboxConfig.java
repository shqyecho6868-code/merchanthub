package com.merchanthub.order.mq.outbox;

import com.merchanthub.order.mq.OrderEventTopics;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "merchanthub.order.event-mode", havingValue = "OUTBOX")
public class OutboxConfig {

    /** 普通生产者即可：一致性靠本地消息表，不需要 MQ 的事务能力 */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer orderOutboxProducer(@Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr) {
        DefaultMQProducer producer = new DefaultMQProducer("order-outbox-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(3000);
        producer.setTopics(List.of(OrderEventTopics.TOPIC));   // 启动时预取路由（P18）
        return producer;
    }

    /**
     * 提交后"立即发一次"用的线程池。队列满了直接丢弃任务：记录还在表里，扫描任务会补发，
     * 不能因为 MQ 慢把下单线程拖住（CallerRuns 会让下单请求自己去发消息）。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService outboxSendExecutor() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000),
                r -> new Thread(r, "outbox-send-" + n.incrementAndGet()), new ThreadPoolExecutor.DiscardPolicy());
    }
}
