package com.merchanthub.order.mq.tx;

import com.merchanthub.order.mq.OrderEventTopics;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
@ConditionalOnProperty(name = "merchanthub.order.event-mode", havingValue = "TX_MESSAGE")
public class TxMessageConfig {

    /**
     * The producer group name stays fixed across environments (default order-tx-producer): on check-back the broker asks "any online instance in the same producer group".
     * After a killed process restarts, as long as the group name matches it can answer check-backs for half messages left before the crash (that's what the kill experiment checks).
     * Conversely, every instance in the group must answer correctly for any half message -- check-back logic may rely only on the shared DB, never on local memory.
     */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public TransactionMQProducer orderTxProducer(OrderTxListener listener,
                                                 @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr,
                                                 @Value("${merchanthub.order.tx.producer-group:order-tx-producer}") String group) {
        TransactionMQProducer producer = new TransactionMQProducer(group);
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        // Half-message send timeout: when MQ is unavailable, fail the order fast instead of hanging the user (cross-service calls must have timeouts)
        producer.setSendMsgTimeout(3000);
        // Prefetch ORDER_EVENT routes at startup: the producer only heartbeats to "brokers with known routes", and routes are fetched on the first send by default.
        // Without prefetching, a restarted instance is invisible to the broker until it places its first order, so check-backs fail with "channel is null",
        // and after 15 check-backs the half message is discarded -- order created, message never sent (hit in a manual kill experiment)
        producer.setTopics(List.of(OrderEventTopics.TOPIC));
        producer.setTransactionListener(listener);
        producer.setExecutorService(checkExecutor());
        return producer;
    }

    /** Check-back thread pool: bounded queue, so a flood of check-backs from the broker can't blow up memory */
    private ExecutorService checkExecutor() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2000),
                r -> new Thread(r, "order-tx-check-" + n.incrementAndGet()));
    }
}
