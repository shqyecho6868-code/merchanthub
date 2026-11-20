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
 * 方案一（事务消息）回查路径。
 * <p>
 * report-unknown=true：本地事务提交后返回 UNKNOW，效果等同于"进程在提交消息前被 kill / COMMIT 没送到 Broker"，
 * 是 🧑 kill 实验的自动化版本。测试 Broker 的回查周期调成了 1 秒、免回查时间下限 3 秒（见 RocketMqContainer）。
 * <p>
 * 用独立的生产者组：同一个 JVM 里还缓存着其他测试类的 Spring 上下文，它们的生产者在默认组里也在线，
 * Broker 回查会找"同组任意实例"——第一次跑这个测试时回查就被别的上下文接走了（B2 实测，见 pitfalls）。
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

    /** 记录每次回查的 (订单号, 结果) */
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

    /** 订单已提交、COMMIT 丢了 → 回查发现订单存在 → COMMIT → 消费者收到。不会出现"订单建了消息没发" */
    @Test
    void commitLost_checkFindsOrder_commits() {
        recordChecks();
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());

        await().atMost(Duration.ofSeconds(60)).until(() -> !observer.received(orderNo).isEmpty());
        assertThat(checks).contains(orderNo + "=" + LocalTransactionState.COMMIT_MESSAGE);
    }

    /**
     * 回查时订单不存在 → ROLLBACK → 消费者永远收不到。
     * 构造方式：下单后、回查前把订单删掉，模拟"本地事务其实没成功"。
     */
    @Test
    void checkFindsNoOrder_rollsBack_neverVisible() {
        recordChecks();
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 102, 1)).getOrderNo());
        jdbc.update("DELETE FROM orders WHERE order_no = ?", orderNo);

        await().atMost(Duration.ofSeconds(60))
                .until(() -> checks.contains(orderNo + "=" + LocalTransactionState.ROLLBACK_MESSAGE));
        // 消息不满 4 秒时的回查只能回答 UNKNOW（第二道防线）；只有消息够老之后才判回滚
        assertThat(checks.indexOf(orderNo + "=" + LocalTransactionState.ROLLBACK_MESSAGE))
                .isGreaterThanOrEqualTo(0);
        // 回滚后再等几个回查周期，确认消息确实没被投递
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(7))
                .until(() -> observer.received(orderNo).isEmpty());
    }
}
