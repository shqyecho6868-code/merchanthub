package com.merchanthub.order;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;

import com.merchanthub.order.mq.payment.OrderPaidService;
import com.merchanthub.order.mq.payment.PaySuccessEvent;
import com.merchanthub.order.mq.timeout.PayTimeoutMessage;
import com.merchanthub.order.mq.timeout.PayTimeoutService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Destructive experiment ② (automated): "payment succeeded" and "timeout close" for the same order arrive at the same time.
 * <p>
 * Conclusion: the order CAS (WHERE status = 10) lets only one of them affect 1 row.
 * <ul>
 *   <li>Payment wins: order 20, only ORDER_PAID is written (inventory confirmed); the timeout side writes nothing</li>
 *   <li>Timeout wins: order 50, only ORDER_CLOSED is written (inventory released); the payment side writes a PAID_AFTER_CLOSED payment anomaly for reconciliation</li>
 * </ul>
 * No order ever has both ORDER_PAID and ORDER_CLOSED -- so inventory can never be "both confirmed and released".
 * Remove {@code AND status = #{from}} from the CAS and rerun: you'll see both events written for the same order and 3 log rows.
 * <p>
 * This test caught a bug on its first run (pitfalls P22): timeout won, yet the payment side's anomaly was recorded as DUPLICATE_PAYMENT / order status 10.
 * Cause: after the CAS failed, the order status was read with a plain SELECT, which under repeatable read sees the transaction's starting snapshot. Fixed by switching to a FOR UPDATE current read.
 */
@SpringBootTest(properties = "merchanthub.order.event-mode=OUTBOX")
class PayTimeoutRaceIT extends OrderItSupport {

    @Autowired
    private OrderPaidService paidService;
    @Autowired
    private PayTimeoutService timeoutService;

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void paySuccessAndTimeoutAtOnce_exactlyOneWins() throws Exception {
        int orders = 30;
        List<Long> orderNos = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            orderNos.add(Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo()));
        }

        ExecutorService pool = Executors.newFixedThreadPool(orders * 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        long base = System.nanoTime();
        for (int i = 0; i < orders; i++) {
            long orderNo = orderNos.get(i);
            long payNo = base + i;
            long timeoutEventId = base + 1_000_000 + i;
            futures.add(pool.submit(() -> {
                start.await();
                return TenantContext.callAs(TenantInfo.systemOf(TENANT), () -> paidService.handle(
                        new PaySuccessEvent(payNo, payNo, orderNo, TENANT, BUYER, 9900, "T" + payNo, LocalDateTime.now())));
            }));
            futures.add(pool.submit(() -> {
                start.await();
                return TenantContext.callAs(TenantInfo.systemOf(TENANT),
                        () -> timeoutService.handle(new PayTimeoutMessage(timeoutEventId, orderNo, TENANT)));
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();   // any exception on either side (deadlock etc.) surfaces here
        }
        pool.shutdown();

        int paid = 0;
        int closed = 0;
        for (long orderNo : orderNos) {
            String key = String.valueOf(orderNo);
            int status = jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo);
            long paidEvents = count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_PAID'", key);
            long closedEvents = count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'", key);
            // With order_status = 50: the recorded "order status at the time" must also be the latest (P22: a snapshot read would record 10 and misclassify as DUPLICATE_PAYMENT)
            long anomalies = count("SELECT COUNT(*) FROM pay_anomaly WHERE order_no = ? AND type = 'PAID_AFTER_CLOSED' AND order_status = 50", orderNo);
            // Status log: CREATE + exactly one transition
            assertThat(count("SELECT COUNT(*) FROM order_status_log WHERE order_no = ?", orderNo)).isEqualTo(2);
            if (status == 20) {
                paid++;
                assertThat(paidEvents).isEqualTo(1);
                assertThat(closedEvents).isZero();
                assertThat(anomalies).isZero();
            } else {
                closed++;
                assertThat(status).isEqualTo(50);
                assertThat(closedEvents).isEqualTo(1);
                assertThat(paidEvents).isZero();
                assertThat(anomalies).isEqualTo(1);   // money received, order not paid: record it for reconciliation
            }
        }
        assertThat(paid + closed).isEqualTo(orders);
        System.out.printf("支付与超时并发 %d 单：支付赢 %d，超时赢 %d%n", orders, paid, closed);
    }

    /** Sequential version: timeout closes first, then payment success arrives -> order not revived, no ORDER_PAID, only a payment anomaly */
    @Test
    void paySuccessAfterClosed_recordedAsAnomaly_orderStaysClosed() {
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        long payNo = System.nanoTime();
        assertThat(timeoutService.handle(new PayTimeoutMessage(payNo + 1, orderNo, TENANT))).isTrue();
        assertThat(paidService.handle(new PaySuccessEvent(payNo, payNo, orderNo, TENANT, BUYER, 9900, "T" + payNo,
                LocalDateTime.now()))).isFalse();

        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo)).isEqualTo(50);
        assertThat(jdbc.queryForMap("SELECT type, order_status, handle_status FROM pay_anomaly WHERE pay_no = ?", payNo))
                .containsEntry("type", "PAID_AFTER_CLOSED").containsEntry("order_status", 50).containsEntry("handle_status", 0);
        assertThat(count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_PAID'", String.valueOf(orderNo))).isZero();

        // Duplicate timeout message: blocked by the dedup table
        assertThat(timeoutService.handle(new PayTimeoutMessage(payNo + 1, orderNo, TENANT))).isFalse();
    }

    /**
     * Sequential version (reverse): payment succeeds first, and the timeout message is still delivered when due.
     * <p>
     * This is the <b>most common</b> path in production: a scheduled message can't be recalled once sent, so every paid order receives a timeout message.
     * The timeout side's CAS(10->50) affects 0 rows -> order stays paid, no log entry, no ORDER_CLOSED (inventory isn't released).
     * Remove {@code AND status = #{from}} from the CAS and rerun: you'll see the paid order closed and inventory confirmed then released (B4 experiment ③).
     */
    @Test
    void timeoutAfterPaid_noop_orderStaysPaid_noClosedEvent() {
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        long payNo = System.nanoTime();
        assertThat(paidService.handle(new PaySuccessEvent(payNo, payNo, orderNo, TENANT, BUYER, 9900, "T" + payNo,
                LocalDateTime.now()))).isTrue();

        assertThat(timeoutService.handle(new PayTimeoutMessage(payNo + 1, orderNo, TENANT))).isFalse();

        String key = String.valueOf(orderNo);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT close_reason FROM orders WHERE order_no = ?", String.class, orderNo)).isNull();
        // Status log: CREATE + PAY_SUCCESS, no PAY_TIMEOUT
        assertThat(count("SELECT COUNT(*) FROM order_status_log WHERE order_no = ?", orderNo)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_PAID'", key)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'", key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM pay_anomaly WHERE order_no = ?", orderNo)).isZero();
    }

    /**
     * Tenant isolation: a timeout message with the right order number but the wrong tenant can't close the order.
     * The order CAS WHERE clause includes tenant_id, so even a tampered / cross-tenant message body affects 0 rows.
     * A message with the correct tenant can still close the order afterwards (the bad message didn't "use up" anything).
     */
    @Test
    void timeoutWithOtherTenant_cannotCloseOrder() {
        long orderNo = Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
        long otherTenant = TENANT + 1;
        long eventId = System.nanoTime();

        assertThat(timeoutService.handle(new PayTimeoutMessage(eventId, orderNo, otherTenant))).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo)).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'",
                String.valueOf(orderNo))).isZero();

        assertThat(timeoutService.handle(new PayTimeoutMessage(eventId + 1, orderNo, TENANT))).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo)).isEqualTo(50);
    }
}
