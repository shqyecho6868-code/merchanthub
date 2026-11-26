package com.merchanthub.order.mq.outbox;

import com.merchanthub.order.mq.OrderEventTopics;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Since B3-下 the outbox is order's generic outbox (paid / closed / timeout-close messages all use it) and no longer follows the event-mode switch;
 * event-mode only decides whether the "order created" event uses transactional messages or the local message table
 */
@Configuration
public class OutboxConfig {

    /** A plain producer is enough: consistency comes from the local message table, no MQ transactional capability needed */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer orderOutboxProducer(@Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr) {
        DefaultMQProducer producer = new DefaultMQProducer("order-outbox-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(3000);
        producer.setTopics(List.of(OrderEventTopics.TOPIC, OrderEventTopics.TIMEOUT_TOPIC));   // prefetch routes at startup (P18)
        return producer;
    }

    /**
     * Thread pool for the post-commit "send once immediately". When the queue is full the task is simply dropped: the record is still in the table and the scan will re-send it;
     * a slow MQ must not hold up order threads (CallerRuns would make the order request send the message itself).
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService outboxSendExecutor() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000),
                r -> new Thread(r, "outbox-send-" + n.incrementAndGet()), new ThreadPoolExecutor.DiscardPolicy());
    }
}
