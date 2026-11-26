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
 * Destructive experiment ②: with the local message table approach, MQ is unavailable; observe how the compensation scan re-sends.
 * <p>
 * "MQ unavailable" is simulated with a spy making the producer's first N sends throw MQClientException (the same kind of exception the client gets when the broker is down).
 * We don't stop the shared RocketMQ container: other test classes in the same JVM use it. The manual experiment that really stops the broker was done by hand.
 * <p>
 * Backoff set to 1s and scan every 500ms so several retries finish within seconds.
 * The scan is scheduled by XXL-Job and the service has no timer of its own: here a background thread calls relay.scan() every 500ms
 * to simulate the admin trigger. Only this context's relay (with the spy producer) scans, so other cached contexts can't steal the re-send.
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

    /** Simulates XXL-Job: triggers orderOutboxRelayJob every 500ms */
    @BeforeEach
    void startScanTrigger() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                relay.scan();
            } catch (RuntimeException ignored) {
                // Like the admin: one failed run doesn't affect the next
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
     * The first failTimes sends of the "order created" event fail, then it recovers.
     * Count only ORDER_EVENT: each order also writes an ORDER_TIMEOUT scheduled message row, which must not consume the failure count
     */
    private void mqDownFor(int failTimes) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (OrderEventTopics.TOPIC.equals(inv.<Message>getArgument(0).getTopic()) && calls.incrementAndGet() <= failTimes) {
                throw new MQClientException(-1, "simulated MQ unavailable");
            }
            return inv.callRealMethod();
        }).when(producer).send(any(Message.class));
    }

    private Map<String, Object> outbox(long orderNo) {
        return jdbc.queryForMap("SELECT * FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CREATED'", String.valueOf(orderNo));
    }

    /**
     * MQ down: orders still succeed (the biggest advantage over the transactional-message approach); fast path fails -> scan retries with backoff -> delivered after MQ recovers.
     * Conclusion: retry_count records the failures, last_error keeps the last failure reason, finally status=1 and the consumer receives it.
     * (Before the fix this failed: after the first failure the record was stuck at status=1, retry_count=0, and the consumer never received it)
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
        assertThat((String) row.get("last_error")).contains("simulated MQ unavailable");
        assertThat(row.get("sent_at")).isNotNull();
    }

    /** MQ never recovers: after max retries it's marked failed (2), no infinite retries, and never wrongly marked as sent */
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
     * Two "instances" re-send the same record at once: the claiming conditional update lets only one succeed, so the message is sent once.
     * Built while MQ is healthy: first make the fast path fail once (record back to pending, due in 1s), then publish from 8 threads at once.
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
                TenantContext.runAsSystem(() -> relay.publish(row));   // the worker thread sets its own context
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        await().atMost(Duration.ofSeconds(30)).until(() -> !observer.received(orderNo).isEmpty());
        // Wait a few more scan cycles to confirm there's no second one
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .until(() -> observer.received(orderNo).size() == 1);
    }
}
