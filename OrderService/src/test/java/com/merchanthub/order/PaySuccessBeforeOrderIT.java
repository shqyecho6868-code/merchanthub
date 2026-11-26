package com.merchanthub.order;

import com.merchanthub.common.tenant.mq.TenantMessages;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.order.mq.payment.OrderNotVisibleException;
import com.merchanthub.order.mq.payment.OrderPaidService;
import com.merchanthub.order.mq.payment.PaySuccessEvent;
import com.merchanthub.order.service.NewOrder;
import com.merchanthub.order.service.OrderPlacer;
import com.merchanthub.order.service.OrderPricing;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Destructive experiment ① (automated): the payment-success event arrives before the order.
 * <p>
 * Conclusion: when the order doesn't exist, {@link OrderNotVisibleException} is thrown -> consumer transaction rolls back (dedup record undone too) -> RocketMQ redelivers ->
 * some redelivery after the order is persisted succeeds, and the order becomes paid.
 * Before the fix (return false when the order doesn't exist): the dedup record committed and the message was acked, so the order never got this payment after being persisted and was eventually closed for timeout -- money received, order gone.
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
        "merchanthub.order.pay-consumer.enabled=true",
        "merchanthub.order.pay-consumer.consume-from=CONSUME_FROM_FIRST_OFFSET"})
class PaySuccessBeforeOrderIT extends OrderItSupport {

    private static DefaultMQProducer producer;

    @Autowired
    private OrderPaidService paidService;
    @Autowired
    private OrderPlacer orderPlacer;
    @Autowired
    private ObjectMapper objectMapper;
    /** The app's UTC clock. Can't use LocalDateTime.now(): this machine is on PST, so expire_at would land 8 hours in the past -> timeout message delivered immediately (P24) */
    @Autowired
    private Clock clock;

    @BeforeAll
    static void start() throws Exception {
        SharedContainers.rocketmq().createTopic(PaySuccessEvent.TOPIC);
        producer = new DefaultMQProducer("it-pay-early-producer");
        producer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        producer.setVipChannelEnabled(false);
        producer.start();
    }

    @AfterAll
    static void stop() {
        producer.shutdown();
    }

    /** Persist an order with a given order number (in the real flow the server generates it; the test needs to know it in advance) */
    private void placeOrder(long orderNo) {
        Map<Long, Integer> qty = new TreeMap<>(Map.of(101L, 1));
        OrderPricing.Result pricing = OrderPricing.price(qty, catalog);
        LocalDateTime now = LocalDateTime.now(clock);
        orderPlacer.place(new NewOrder(orderNo, TENANT, BUYER, pricing, now, now.plusMinutes(15)));
    }

    private PaySuccessEvent event(long orderNo) {
        long payNo = System.nanoTime();
        return new PaySuccessEvent(payNo, payNo, orderNo, TENANT, BUYER, 9900, "T" + payNo, LocalDateTime.now());
    }

    @Test
    void orderMissing_throws_andDedupRecordRolledBack() {
        long orderNo = System.nanoTime();
        PaySuccessEvent e = event(orderNo);
        assertThatThrownBy(() -> paidService.handle(e)).isInstanceOf(OrderNotVisibleException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", Long.class, e.eventId())).isZero();

        // The same event again after the order appears (simulated redelivery): processed normally
        placeOrder(orderNo);
        assertThat(paidService.handle(e)).isTrue();
    }

    @Test
    void endToEnd_eventArrivesFirst_redeliveredAfterOrderCommitted_orderPaid() throws Exception {
        long orderNo = System.nanoTime();
        producer.send(TenantMessages.stamp(
                new Message(PaySuccessEvent.TOPIC, PaySuccessEvent.TAG, objectMapper.writeValueAsBytes(event(orderNo))), TENANT));

        // Wait until the consumer has processed it at least once (failed, awaiting redelivery) before persisting the order
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(countOrders(orderNo)).isZero();
        placeOrder(orderNo);

        // The first redelivery is 10s later (then 30s, 1m...)
        await().atMost(Duration.ofSeconds(90)).until(() ->
                jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo) == 20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_PAID'",
                Long.class, String.valueOf(orderNo))).isEqualTo(1);
    }
}
