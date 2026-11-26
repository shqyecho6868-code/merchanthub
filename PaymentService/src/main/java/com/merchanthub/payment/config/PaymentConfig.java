package com.merchanthub.payment.config;

import com.merchanthub.payment.mq.PaymentEventTopics;
import com.merchanthub.payment.mq.PaymentTxListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class PaymentConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * payment uses a transactional message to publish "payment succeeded" (order uses a local message table by default; both approaches are covered in B2). Why payment picks transactional messages, see D30:
     * the local transaction is a single CAS, and the check-back only needs "look up the payment by pay_no and see whether it succeeded" — simple and reliable, no outbox table and scan job to maintain.
     * The rest of the config follows order's TxMessageConfig (fixed group name, route prefetch P18, send timeout).
     */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public TransactionMQProducer paymentTxProducer(PaymentTxListener listener,
                                                   @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr) {
        TransactionMQProducer producer = new TransactionMQProducer("payment-tx-producer");
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        producer.setSendMsgTimeout(3000);
        producer.setTopics(List.of(PaymentEventTopics.TOPIC));
        producer.setTransactionListener(listener);
        AtomicInteger n = new AtomicInteger();
        producer.setExecutorService(new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2000),
                r -> new Thread(r, "payment-tx-check-" + n.incrementAndGet())));
        return producer;
    }
}
