package com.merchanthub.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end: place order -> scheduled message in outbox handed to the broker -> delivered when due -> timeout close -> ORDER_CLOSED event written.
 * Also verifies that RocketMQ 5.3.1's arbitrary-delay scheduled messages (setDeliverTimeMs) work with default broker config and aren't delivered early.
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
        "merchanthub.order.pay-timeout=6s",
        "merchanthub.order.timeout-consumer.enabled=true"})
class PayTimeoutIT extends OrderItSupport {

    private int status(long orderNo) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo);
    }

    @Test
    void unpaidOrder_closedAfterExpire_andClosedEventWritten() {
        long start = System.currentTimeMillis();
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());

        // The timeout message is written to outbox in the same transaction as the order, with delivery time = expire_at
        Map<String, Object> timeoutRow = jdbc.queryForMap(
                "SELECT o.deliver_at, r.expire_at FROM order_outbox o JOIN orders r ON r.order_no = o.biz_key "
                        + "WHERE o.topic = 'ORDER_TIMEOUT' AND o.biz_key = ?", String.valueOf(orderNo));
        assertThat(timeoutRow.get("deliver_at")).isEqualTo(timeoutRow.get("expire_at"));

        // Must not be delivered early: still pending at 3s
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(4)).until(() -> true);
        assertThat(status(orderNo)).isEqualTo(10);

        await().atMost(Duration.ofSeconds(60)).until(() -> status(orderNo) == 50);
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(6_000);

        assertThat(jdbc.queryForObject("SELECT close_reason FROM orders WHERE order_no = ?", String.class, orderNo))
                .isEqualTo("PAY_TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_status_log WHERE order_no = ? AND event = 'PAY_TIMEOUT'",
                Long.class, orderNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'",
                Long.class, String.valueOf(orderNo))).isEqualTo(1);
    }
}
