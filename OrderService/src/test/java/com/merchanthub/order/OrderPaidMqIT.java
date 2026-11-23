package com.merchanthub.order;

import com.merchanthub.common.tenant.mq.TenantMessages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.order.mq.payment.PaySuccessEvent;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 端到端：PAYMENT_EVENT 上的"支付成功"消息（重复投递 3 次）→ 消费者 → 订单只变成已支付一次 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
        "merchanthub.order.pay-consumer.enabled=true",
        "merchanthub.order.pay-consumer.consume-from=CONSUME_FROM_FIRST_OFFSET"})
class OrderPaidMqIT extends OrderItSupport {

    private static DefaultMQProducer producer;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void start() throws Exception {
        SharedContainers.rocketmq().createTopic(PaySuccessEvent.TOPIC);
        producer = new DefaultMQProducer("it-pay-raw-producer");
        producer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        producer.setVipChannelEnabled(false);
        producer.start();
    }

    @AfterAll
    static void stop() {
        producer.shutdown();
    }

    @Test
    void duplicatedDelivery_orderPaidOnce() throws Exception {
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        long payNo = System.nanoTime();
        byte[] body = objectMapper.writeValueAsBytes(
                new PaySuccessEvent(payNo, payNo, orderNo, TENANT, BUYER, 9900, "T" + payNo, LocalDateTime.now()));
        for (int i = 0; i < 3; i++) {
            producer.send(TenantMessages.stamp(new Message(PaySuccessEvent.TOPIC, PaySuccessEvent.TAG, body), TENANT));
        }
        await().atMost(Duration.ofSeconds(60)).until(() ->
                jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo) == 20);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10)).until(() -> true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", Long.class, payNo)).isEqualTo(1);
    }
}
