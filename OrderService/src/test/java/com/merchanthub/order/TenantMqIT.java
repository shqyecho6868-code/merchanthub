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
 * Integration test: during MQ consumption the tenant context comes from <b>message properties</b> and is really applied to the SQL.
 * Three contrasting cases using the timeout-close consumer (real RocketMQ); all orders belong to merchant 7:
 * <ol>
 *   <li>Message with tenantId=7 -> order closed</li>
 *   <li>Message with tenantId=8 (doesn't match the order) -> consumed, but the CAS SQL gets tenant_id = 8 and can't touch merchant 7's order</li>
 *   <li>Message without tenantId -> not processed at all (fail-closed, redelivered until the DLQ)</li>
 * </ol>
 * Only DB results are checked, the consumer isn't spied: other cached Spring contexts may have same-group consumers taking messages, but whoever consumes, the result should be the same.
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

        // ① Correct merchant: closed
        await().atMost(Duration.ofSeconds(60)).until(() -> status(right) == 50);
        // ② Wrong merchant: the message was consumed (dedup record written) but the order wasn't changed -- the SQL's tenant_id is the 8 from the message
        await().atMost(Duration.ofSeconds(60)).until(() -> consumed(base + 2) == 1);
        assertThat(status(wrong)).isEqualTo(10);
        // ③ No merchant: not processed
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> true);
        assertThat(consumed(base + 1)).isZero();
        assertThat(status(missing)).isEqualTo(10);
    }
}
