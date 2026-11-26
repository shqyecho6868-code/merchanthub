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
 * Automated version of the manual kill experiment: "the old process dies after committing the local transaction, and the restarted process picks up the check-back".
 * <p>
 * Setup: a bare TransactionMQProducer plays "the old process about to be killed" -- after the local transaction writes the order it returns UNKNOW,
 * then shuts down immediately (as if the process were gone). The producer in the Spring context plays "the restarted process": same producer group,
 * and it has <b>never sent a single message</b>.
 * <p>
 * Conclusion: the new process must pick up the check-back and COMMIT. This step failed in the manual experiment -- a producer that has never sent doesn't heartbeat to the broker,
 * so check-back fails with "channel is null" and the half message is discarded after 15 check-backs. Fix: prefetch Topic routes at startup.
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
                // Local transaction commits: write the order
                jdbc.update("INSERT INTO orders (tenant_id, order_no, buyer_id, status, total_amount, pay_amount, expire_at)"
                        + " VALUES (?, ?, ?, 10, 100, 100, NOW(3))", TENANT, orderNo, BUYER);
                return LocalTransactionState.UNKNOW;   // COMMIT never got sent
            }

            @Override
            public LocalTransactionState checkLocalTransaction(MessageExt msg) {
                return LocalTransactionState.UNKNOW;
            }
        });
        dying.start();
        dying.sendMessageInTransaction(codec.toMessage(
                new OrderCreatedEvent(9_000_000_002L, orderNo, TENANT, BUYER, 100, LocalDateTime.now())), null);
        dying.shutdown();   // the process is gone

        // The new process (the Spring context's producer, which has never sent anything) picks up the check-back -> finds the order -> COMMIT -> consumer receives it
        await().atMost(Duration.ofSeconds(60)).until(() -> !observer.received(orderNo).isEmpty());
    }
}
