package com.merchanthub.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.order.mq.OrderEventTopics;
import com.merchanthub.order.mq.timeout.PayTimeoutMessage;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * C1 集成测试：MQ 消费时，租户上下文来自<b>消息属性</b>，并且真的被用在了 SQL 上。
 * 用超时关单消费者（真实 RocketMQ）做三组对照，订单都属于商家 7：
 * <ol>
 *   <li>消息带 tenantId=7 → 关单成功</li>
 *   <li>消息带 tenantId=8（和订单不符）→ 消费了，但 CAS 的 SQL 被拼上 tenant_id = 8，改不到商家 7 的订单</li>
 *   <li>消息没带 tenantId → 根本不处理（fail-closed，重投直到死信）</li>
 * </ol>
 * 只看数据库结果，不 spy 消费者：其他缓存的 Spring 上下文里可能有同组消费者分走消息，但不管谁消费，结果都应一致。
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
        "merchanthub.order.timeout-consumer.enabled=true"})
class TenantMqIT extends OrderItSupport {

    private static DefaultMQProducer producer;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void startProducer() throws Exception {
        producer = new DefaultMQProducer("it-tenant-mq-producer");
        producer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        producer.setVipChannelEnabled(false);
        producer.start();
    }

    @AfterAll
    static void stopProducer() {
        producer.shutdown();
    }

    private long newOrder() {
        return Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
    }

    private void sendTimeout(long eventId, long orderNo, Long stampTenant) throws Exception {
        Message m = new Message(OrderEventTopics.TIMEOUT_TOPIC, OrderEventTopics.TAG_PAY_TIMEOUT,
                objectMapper.writeValueAsBytes(new PayTimeoutMessage(eventId, orderNo, TENANT)));
        if (stampTenant != null) {
            TenantMessages.stamp(m, stampTenant);
        }
        producer.send(m);
    }

    private int status(long orderNo) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo);
    }

    private long consumed(long eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", Long.class, eventId);
    }

    @Test
    void consumerRestoresTenantFromMessage() throws Exception {
        long base = System.nanoTime();
        long missing = newOrder();
        long wrong = newOrder();
        long right = newOrder();

        sendTimeout(base + 1, missing, null);
        sendTimeout(base + 2, wrong, 8L);
        sendTimeout(base + 3, right, TENANT);

        // ① 正确商家：关单
        await().atMost(Duration.ofSeconds(60)).until(() -> status(right) == 50);
        // ② 错误商家：消息被消费了（去重记录写入），但订单没被改 —— SQL 里的 tenant_id 用的是消息里的 8
        await().atMost(Duration.ofSeconds(60)).until(() -> consumed(base + 2) == 1);
        assertThat(status(wrong)).isEqualTo(10);
        // ③ 没带商家：不处理
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> true);
        assertThat(consumed(base + 1)).isZero();
        assertThat(status(missing)).isEqualTo(10);
    }
}
