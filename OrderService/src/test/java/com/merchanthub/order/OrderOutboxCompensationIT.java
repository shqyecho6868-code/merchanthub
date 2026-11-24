package com.merchanthub.order;

import com.merchanthub.common.tenant.TenantContext;

import com.merchanthub.order.mapper.OrderOutboxMapper;
import com.merchanthub.order.model.entity.OrderOutbox;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.mq.outbox.OutboxRelay;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import java.util.concurrent.ScheduledExecutorService;
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
 * E3 起扫描由 XXL-Job 调度，服务里不再有自己的定时器：这里用一个后台线程每 500 毫秒调一次 relay.scan()，
 * 模拟调度中心触发。只扫这个上下文的 relay（带 spy 生产者），不会被别的缓存上下文抢走补发（P17）。
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
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

    private ScheduledExecutorService scheduler;

    @BeforeAll
    static void startObserver() throws Exception {
        observer = new OrderEventObserver();
    }

    /** 模拟 XXL-Job：每 500 毫秒触发一次 orderOutboxRelayJob */
    @BeforeEach
    void startScanTrigger() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                relay.scan();
            } catch (RuntimeException ignored) {
                // 和调度中心一样：一次执行失败不影响下一次
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    @AfterEach
    void stopScanTrigger() {
        scheduler.shutdownNow();
    }

    @AfterAll
    static void stopObserver() {
        observer.close();
    }

    /**
     * "订单已创建"事件的前 failTimes 次发送失败，之后恢复正常。
     * 只对 ORDER_EVENT 计数：B3-下起每单还会写一行 ORDER_TIMEOUT 定时消息，不能让它分走失败次数
     */
    private void mqDownFor(int failTimes) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (OrderEventTopics.TOPIC.equals(inv.<Message>getArgument(0).getTopic()) && calls.incrementAndGet() <= failTimes) {
                throw new MQClientException(-1, "模拟 MQ 不可用");
            }
            return inv.callRealMethod();
        }).when(producer).send(any(Message.class));
    }

    private Map<String, Object> outbox(long orderNo) {
        return jdbc.queryForMap("SELECT * FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CREATED'", String.valueOf(orderNo));
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
                TenantContext.runAsSystem(() -> relay.publish(row));   // 子线程自己设上下文（C1）
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
