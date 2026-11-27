package com.merchanthub.order.mq.outbox;

import com.merchanthub.order.mq.OrderEventTopics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The outbox is order's generic outbox (paid / closed / timeout-close messages all use it) and no longer follows the event-mode switch;
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
        producer.setTopics(List.of(OrderEventTopics.TOPIC, OrderEventTopics.TIMEOUT_TOPIC));   // prefetch routes at startup
        return producer;
    }

    /**
     * Thread pool for the post-commit "send once immediately". When the queue is full the task is dropped (still DiscardPolicy semantics):
     * the record is already in the table and the scan will re-send it; a slow MQ must not hold up order threads
     * (CallerRuns would make the order request send the message itself, a blocking queue would make it wait).
     * Drops are counted and logged by {@link FastPathDropHandler}, so "the fast path is dropping" is visible instead of silent.
     * Sizes are configurable so tests and local drills can shrink the pool and force drops.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService outboxSendExecutor(MeterRegistry registry,
                                              @Value("${merchanthub.order.outbox.fast-path.core-threads:2}") int core,
                                              @Value("${merchanthub.order.outbox.fast-path.max-threads:4}") int max,
                                              @Value("${merchanthub.order.outbox.fast-path.queue-capacity:1000}") int queue) {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(core, max, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                r -> new Thread(r, "outbox-send-" + n.incrementAndGet()), new FastPathDropHandler(registry));
    }

    /**
     * Pool used only by the scan when it is catching up on a backlog (see {@link OutboxRelay#scan}).
     * Kept small on purpose: every thread holds a DB connection per send (claim + markSent), and order's Hikari pool is only 10;
     * more threads would drain faster but starve order placement of connections.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService outboxCatchUpExecutor(@Value("${merchanthub.order.outbox.catch-up.parallelism:4}") int parallelism) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(parallelism, r -> new Thread(r, "outbox-catchup-" + n.incrementAndGet()));
    }
}
