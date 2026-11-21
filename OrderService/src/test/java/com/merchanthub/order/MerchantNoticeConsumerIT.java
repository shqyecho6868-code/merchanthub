package com.merchanthub.order;

import com.merchanthub.order.mq.OrderCreatedEvent;
import com.merchanthub.order.mq.OrderEventCodec;
import com.merchanthub.order.mq.consumer.MerchantNoticeService;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 消费端幂等 + 破坏性实验：重复投递、业务处理失败后重投。
 * 消费者用 FIRST_OFFSET：测试里消费者启动和发消息几乎同时，用 LAST 可能在分到队列之前就错过消息。
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
        "merchanthub.order.notice-consumer.enabled=true",
        "merchanthub.order.notice-consumer.consume-from=CONSUME_FROM_FIRST_OFFSET"})
class MerchantNoticeConsumerIT extends OrderItSupport {

    private static final AtomicLong IDS = new AtomicLong(System.currentTimeMillis() * 1000);
    private static DefaultMQProducer rawProducer;

    @MockitoSpyBean
    private MerchantNoticeService service;

    @Autowired
    private OrderEventCodec codec;

    @BeforeAll
    static void startProducer() throws Exception {
        rawProducer = new DefaultMQProducer("it-notice-raw-producer");
        rawProducer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        rawProducer.setVipChannelEnabled(false);
        rawProducer.start();
    }

    @AfterAll
    static void stopProducer() {
        rawProducer.shutdown();
    }

    /** 用例中途失败时，把改名的通知表恢复回来，免得影响后面的用例 */
    @AfterEach
    void restoreTable() {
        Long off = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = 'merchant_order_notice_off'", Long.class);
        if (off != null && off > 0) {
            jdbc.execute("RENAME TABLE merchant_order_notice_off TO merchant_order_notice");
        }
    }

    private OrderCreatedEvent newEvent() {
        return new OrderCreatedEvent(IDS.incrementAndGet(), IDS.incrementAndGet(), TENANT, BUYER, 9900, LocalDateTime.now());
    }

    private long count(String table, String col, long value) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + col + " = ?", Long.class, value);
        return n == null ? 0 : n;
    }

    /** 端到端：本地消息表方案下单 → 消费者写出一条商家通知 */
    @Test
    void placeOrder_noticeWritten() {
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        await().atMost(Duration.ofSeconds(60)).until(() -> count("merchant_order_notice", "order_no", orderNo) == 1);
    }

    /**
     * 破坏性：同一个事件被投递 3 次（模拟本地消息表"发成功但响应超时 → 补偿重发"、事务消息回查后重复提交）。
     * 3 条消息的 msgId 各不相同，event_id 相同。
     * 结论：处理方法被调用了 3 次，但只写了 1 条通知、1 条去重记录 —— 按 msgId 去重的话这里会写 3 条。
     */
    @Test
    void sameEventDeliveredThreeTimes_differentMsgIds_noticeWrittenOnce() throws Exception {
        OrderCreatedEvent event = newEvent();
        Set<String> msgIds = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            SendResult r = rawProducer.send(codec.toMessage(event));
            msgIds.add(r.getMsgId());
        }
        assertThat(msgIds).hasSize(3);

        verify(service, timeout(60_000).times(3)).handle(argThat(e -> e != null && e.eventId() == event.eventId()));
        assertThat(count("merchant_order_notice", "event_id", event.eventId())).isEqualTo(1);
        assertThat(count("consumed_event", "event_id", event.eventId())).isEqualTo(1);
    }

    /**
     * 破坏性：业务处理失败（把通知表改名，模拟下游库表故障）→ 去重记录随事务一起回滚 → 修复后 RocketMQ 重投 → 正常处理一次。
     * 结论：失败那次没有留下去重记录。如果去重记录不在同一个事务里（比如用 Redis SETNX 先占位），
     * 重投时会被当成"已处理"跳过，这条通知就永远丢了。
     */
    @Test
    void businessFails_dedupRolledBack_redeliveredAndProcessedOnce() throws Exception {
        jdbc.execute("RENAME TABLE merchant_order_notice TO merchant_order_notice_off");
        OrderCreatedEvent event = newEvent();
        rawProducer.send(codec.toMessage(event));

        verify(service, timeout(60_000).atLeast(1)).handle(argThat(e -> e != null && e.eventId() == event.eventId()));
        // verify 在 handle() 被"调用"时就通过，那次处理可能还没执行到写通知表；不等它跑完就恢复表，
        // 它会直接成功，测试看不到失败 → 重投（B3 全量构建时偶发，pitfalls P21）
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> true);
        // 失败的那次处理：去重记录已回滚
        assertThat(count("consumed_event", "event_id", event.eventId())).isZero();

        jdbc.execute("RENAME TABLE merchant_order_notice_off TO merchant_order_notice");   // 故障恢复
        // RocketMQ 第一次重投默认延迟 10 秒
        await().atMost(Duration.ofSeconds(60))
                .until(() -> count("merchant_order_notice", "event_id", event.eventId()) == 1);
        assertThat(count("consumed_event", "event_id", event.eventId())).isEqualTo(1);
        verify(service, atLeast(2)).handle(argThat(e -> e != null && e.eventId() == event.eventId()));
    }
}
