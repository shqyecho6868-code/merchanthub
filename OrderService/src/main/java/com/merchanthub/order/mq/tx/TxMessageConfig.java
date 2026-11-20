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
     * 生产者组名在各环境保持固定（默认 order-tx-producer）：Broker 回查时，是找"同一个生产者组里任意一个在线的实例"来问。
     * 进程被 kill 后重启，只要组名一样，就能接住宕机前留下的半消息的回查（kill 实验看的就是这个）。
     * 反过来，组里的每个实例都必须能正确回答任何一条半消息 —— 回查逻辑只能依赖共享的数据库，不能依赖本机内存。
     */
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public TransactionMQProducer orderTxProducer(OrderTxListener listener,
                                                 @Value("${merchanthub.rocketmq.namesrv-addr}") String namesrvAddr,
                                                 @Value("${merchanthub.order.tx.producer-group:order-tx-producer}") String group) {
        TransactionMQProducer producer = new TransactionMQProducer(group);
        producer.setNamesrvAddr(namesrvAddr);
        producer.setVipChannelEnabled(false);
        // 发半消息的超时：MQ 不可用时让下单快速失败，而不是卡住用户（跨服务调用必须有超时）
        producer.setSendMsgTimeout(3000);
        // 启动时就预取 ORDER_EVENT 的路由：生产者只给"已知路由的 Broker"发心跳，而路由默认要等第一次发消息才拉取。
        // 不预取的话，宕机重启后的实例在下第一单之前 Broker 都看不到它，回查报 "channel is null"，
        // 半消息回查满 15 次就被丢弃 —— 订单建了消息没发（B2 kill 实验踩到，pitfalls P18）
        producer.setTopics(List.of(OrderEventTopics.TOPIC));
        producer.setTransactionListener(listener);
        producer.setExecutorService(checkExecutor());
        return producer;
    }

    /** 回查线程池：有界队列，Broker 一次推来大量回查时不会把内存撑爆 */
    private ExecutorService checkExecutor() {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2000),
                r -> new Thread(r, "order-tx-check-" + n.incrementAndGet()));
    }
}
