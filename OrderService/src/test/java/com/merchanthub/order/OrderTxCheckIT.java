package com.merchanthub.order;

import com.merchanthub.order.mq.tx.OrderTxListener;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Option 1 (transactional message) check-back path.
 * <p>
 * report-unknown=true: return UNKNOW after the local commit, equivalent to "process killed before committing the message / COMMIT never reached the broker",
 * i.e. an automated version of the 🧑 kill experiment. The test broker's check-back interval is 1s and the minimum check-immunity time 3s (see RocketMqContainer).
 * <p>
 * Uses a separate producer group: the same JVM caches other test classes' Spring contexts, whose producers are also online in the default group,
 * and the broker check-back goes to "any instance in the group" -- the first run of this test had its check-back taken by another context (observed in B2, see pitfalls).
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=TX_MESSAGE",
        "merchanthub.order.tx.debug.report-unknown=true",
        "merchanthub.order.tx.check-immunity-seconds=1",
        "merchanthub.order.tx.rollback-min-age-seconds=4",
        "merchanthub.order.tx.producer-group=it-order-tx-check"})
class OrderTxCheckIT extends OrderItSupport {

    private static OrderEventObserver observer;

    @MockitoSpyBean
    private OrderTxListener listener;

    /** Records each check-back's (order number, result) */
    private final List<String> checks = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startObserver() throws Exception {
        observer = new OrderEventObserver();
    }

    @AfterAll
    static void stopObserver() {
        observer.close();
    }

    private void recordChecks() {
        doAnswer(inv -> {
            LocalTransactionState state = (LocalTransactionState) inv.callRealMethod();
            MessageExt m = inv.getArgument(0);
            checks.add(m.getKeys().split(" ")[0] + "=" + state);
            return state;
        }).when(listener).checkLocalTransaction(any());
    }

    /** Order committed, COMMIT lost -> check-back finds the order -> COMMIT -> consumer receives it. Never "order created, message not sent" */
    @Test
    void commitLost_checkFindsOrder_commits() {
        recordChecks();
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());

        await().atMost(Duration.ofSeconds(60)).until(() -> !observer.received(orderNo).isEmpty());
        assertThat(checks).contains(orderNo + "=" + LocalTransactionState.COMMIT_MESSAGE);
    }

    /**
     * Order doesn't exist at check-back -> ROLLBACK -> the consumer never receives it.
     * Setup: delete the order after placement and before check-back, simulating "the local transaction didn't actually succeed".
     */
    @Test
    void checkFindsNoOrder_rollsBack_neverVisible() {
        recordChecks();
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 102, 1)).getOrderNo());
        jdbc.update("DELETE FROM orders WHERE order_no = ?", orderNo);

        await().atMost(Duration.ofSeconds(60))
                .until(() -> checks.contains(orderNo + "=" + LocalTransactionState.ROLLBACK_MESSAGE));
        // While the message is younger than 4s, check-back can only answer UNKNOW (second line of defense); rollback is decided only once it's old enough
        assertThat(checks.indexOf(orderNo + "=" + LocalTransactionState.ROLLBACK_MESSAGE))
                .isGreaterThanOrEqualTo(0);
        // After rollback, wait a few more check-back cycles to confirm the message really wasn't delivered
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(7))
                .until(() -> observer.received(orderNo).isEmpty());
    }
}
