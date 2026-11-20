package com.merchanthub.order;

import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import com.merchanthub.order.mq.outbox.OutboxRelay;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * 破坏性实验 ②：本地消息表方案下 MQ 不可用，看补偿扫描如何重发。
 * <p>
 * "MQ 不可用"用 spy 让生产者前 N 次 send 抛 MQClientException 来模拟（和 Broker 宕机时客户端拿到的异常同类）。
 * 不直接停共享的 RocketMQ 容器：同一个 JVM 里其他测试类也在用它。真实停 Broker 的手动实验记录在 B2 笔记 7.4。
 * <p>
 * 退避调成 1 秒、扫描 500 毫秒一次，让几次重试在几秒内跑完。
 * 测试配置默认关闭扫描、只有这里打开：第一次跑时，补发全被别的缓存上下文（没 spy 的生产者）抢走了，
 * 这在生产里等价于"别的实例接手补发"，是正常行为，但会让这里的断言失真。
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
        "merchanthub.order.outbox.scan-enabled=true",
        "merchanthub.order.outbox.scan-interval-ms=500",
        "merchanthub.order.outbox.base-backoff-seconds=1",
        "merchanthub.order.outbox.max-backoff-seconds=1",
        "merchanthub.order.outbox.max-retries=4"})
class OrderOutboxCompensationIT extends OrderItSupport {

    private static OrderEventObserver observer;

    @MockitoSpyBean
    private DefaultMQProducer producer;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OrderOutboxMapper outboxMapper;

    @BeforeAll
    static void startObserver() throws Exception {
        observer = new OrderEventObserver();
    }

    @AfterAll
    static void stopObserver() {
        observer.close();
    }

    /** 前 failTimes 次发送失败，之后恢复正常 */
    private void mqDownFor(int failTimes) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() <= failTimes) {
                throw new MQClientException(-1, "模拟 MQ 不可用");
            }
            return inv.callRealMethod();
        }).when(producer).send(any(Message.class));
    }

    private Map<String, Object> outbox(long orderNo) {
        return jdbc.queryForMap("SELECT * FROM order_outbox WHERE biz_key = ?", String.valueOf(orderNo));
    }

    /**
     * MQ 挂了：下单照样成功（这是相对事务消息方案的最大优势）；快路径失败 → 扫描按退避重试 → MQ 恢复后送达。
     * 结论：retry_count 记下了失败次数，last_error 留着最后一次失败原因，最终 status=1、消费者收到。
     * （修复前这里会失败：第一次失败后记录停在 status=1、retry_count=0，消费者永远收不到）
     */
    @Test
    void mqDown_orderStillSucceeds_scanRetriesUntilDelivered() throws Exception {
        mqDownFor(2);
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        assertThat(countOrders(orderNo)).isEqualTo(1);

        await().atMost(Duration.ofSeconds(60)).until(() -> !observer.received(orderNo).isEmpty());
        await().atMost(Duration.ofSeconds(5)).until(() -> ((Number) outbox(orderNo).get("status")).intValue() == 1);
        Map<String, Object> row = outbox(orderNo);
        assertThat(row.get("retry_count")).isEqualTo(2);
        assertThat((String) row.get("last_error")).contains("模拟 MQ 不可用");
        assertThat(row.get("sent_at")).isNotNull();
    }

    /** MQ 一直不恢复：重试满上限后标记为失败（2），不再无限重试，也不会被误标成已发送 */
    @Test
    void mqDownForever_givesUpAfterMaxRetries_markedFailed() throws Exception {
        mqDownFor(Integer.MAX_VALUE);
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());

        await().atMost(Duration.ofSeconds(60)).until(() -> ((Number) outbox(orderNo).get("status")).intValue() == 2);
        Map<String, Object> row = outbox(orderNo);
        assertThat(row.get("retry_count")).isEqualTo(4);
        assertThat(row.get("sent_at")).isNull();
        verify(producer, atLeast(4)).send(any(Message.class));
    }

    /**
     * 两个"实例"同时补发同一条记录：抢占的条件更新只让一个成功，消息只发一次。
     * 在 MQ 正常时构造：先让快路径失败一次（记录回到待发送、1 秒后到期），再用 8 个线程同时 publish。
     */
    @Test
    void concurrentRelays_sameRow_sentOnce() throws Exception {
        mqDownFor(1);
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        await().atMost(Duration.ofSeconds(10)).until(() -> ((Number) outbox(orderNo).get("retry_count")).intValue() == 1
                || ((Number) outbox(orderNo).get("status")).intValue() == 1);

        OrderOutbox row = outboxMapper.selectById(((Number) outbox(orderNo).get("id")).longValue());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) {
            pool.submit(() -> {
                start.await();
                relay.publish(row);
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        await().atMost(Duration.ofSeconds(30)).until(() -> !observer.received(orderNo).isEmpty());
        // 再等几个扫描周期，确认没有第二条
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> observer.received(orderNo).size() == 1);
    }
}
