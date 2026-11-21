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
     * payment 用事务消息发"支付成功"（order 默认用本地消息表，两种方案见 B2）。payment 选事务消息的理由见 D30：
     * 本地事务只有一条 CAS，回查只需"按 pay_no 查支付单是不是成功"，简单可靠，不用再维护一张 outbox 表和扫描任务。
     * 其余配置理由同 order 的 TxMessageConfig（固定组名、预取路由 P18、发送超时）。
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
