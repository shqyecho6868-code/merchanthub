package com.merchanthub.order;

import com.merchanthub.order.mq.OrderCreatedEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.awaitility.Awaitility.await;

/**
 * 🧑 kill 实验的自动化版本："旧进程提交本地事务后挂掉，新进程重启后接住回查"。
 * <p>
 * 构造：用一个裸的 TransactionMQProducer 扮演"即将被 kill 的旧进程"——本地事务写订单后返回 UNKNOW，
 * 然后立刻 shutdown（相当于进程没了）。Spring 上下文里的生产者扮演"重启后的新进程"：同一个生产者组，
 * 而且<b>一条消息都没发过</b>。
 * <p>
 * 结论：新进程必须能接住回查并 COMMIT。B2 手动实验里这一步失败过 —— 生产者没发过消息就不会给 Broker 发心跳，
 * Broker 回查报 "channel is null"，半消息回查满 15 次后被丢弃（pitfalls P18）。修复：启动时预取 Topic 路由。
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=TX_MESSAGE",
        "merchanthub.order.tx.check-immunity-seconds=1",
        "merchanthub.order.tx.rollback-min-age-seconds=4",
        "merchanthub.order.tx.producer-group=" + OrderTxRestartIT.GROUP})
class OrderTxRestartIT extends OrderItSupport {

    static final String GROUP = "it-order-tx-restart";

    private static OrderEventObserver observer;

    @Autowired
    private OrderEventCodec codec;

    @BeforeAll
    static void startObserver() throws Exception {
        observer = new OrderEventObserver();
    }

    @AfterAll
    static void stopObserver() {
        observer.close();
    }

    @Test
    void oldProcessDiesAfterLocalCommit_freshProcessAnswersCheck() throws Exception {
        long orderNo = 9_000_000_001L;
        TransactionMQProducer dying = new TransactionMQProducer(GROUP);
        dying.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        dying.setVipChannelEnabled(false);
        dying.setTransactionListener(new TransactionListener() {
            @Override
            public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
                // 本地事务提交：写订单
                jdbc.update("INSERT INTO orders (tenant_id, order_no, buyer_id, status, total_amount, pay_amount, expire_at)"
                        + " VALUES (?, ?, ?, 10, 100, 100, NOW(3))", TENANT, orderNo, BUYER);
                return LocalTransactionState.UNKNOW;   // COMMIT 没来得及发出去
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                return LocalTransactionState.UNKNOW;
            }
        });
        dying.start();
        dying.sendMessageInTransaction(codec.toMessage(
                new OrderCreatedEvent(9_000_000_002L, orderNo, TENANT, BUYER, 100, LocalDateTime.now())), null);
        dying.shutdown();   // 进程没了

        // 新进程（Spring 上下文里的生产者，没发过任何消息）接住回查 → 查到订单 → COMMIT → 消费者收到
        await().atMost(Duration.ofSeconds(60)).until(() -> !observer.received(orderNo).isEmpty());
    }
}
