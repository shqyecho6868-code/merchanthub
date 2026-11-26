package com.merchanthub.order;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;

import com.merchanthub.order.model.enums.OrderEvent;
import com.merchanthub.order.mq.payment.OrderPaidService;
import com.merchanthub.order.mq.payment.PaySuccessEvent;
import com.merchanthub.order.service.OrderStatusTransitioner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payment succeeded -> order status CAS + status log. Calls the consumer handler directly, covering "duplicate / late / concurrent" payment-success events.
 */
@SpringBootTest(properties = "merchanthub.order.event-mode=OUTBOX")
class OrderPaidIT extends OrderItSupport {

    private static final AtomicLong PAY_NOS = new AtomicLong(System.currentTimeMillis() * 1000);

    @Autowired
    private OrderPaidService service;

    @Autowired
    private OrderStatusTransitioner transitioner;

    private long placeOrder() {
        return Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
    }

    private PaySuccessEvent paid(long orderNo, long amount) {
        long payNo = PAY_NOS.incrementAndGet();
        return new PaySuccessEvent(payNo, payNo, orderNo, TENANT, BUYER, amount, "T" + payNo, LocalDateTime.now());
    }

    private int status(long orderNo) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo);
    }

    private long logs(long orderNo, String event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_status_log WHERE order_no = ? AND event = ?",
                Long.class, orderNo, event);
    }

    @Test
    void paySuccess_orderPaid_andOneLog() {
        long orderNo = placeOrder();
        assertThat(service.handle(paid(orderNo, 9900))).isTrue();

        assertThat(status(orderNo)).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT paid_at IS NOT NULL FROM orders WHERE order_no = ?", Boolean.class, orderNo)).isTrue();
        assertThat(logs(orderNo, "PAY_SUCCESS")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT from_status, to_status, operator_type FROM order_status_log "
                + "WHERE order_no = ? AND event = 'PAY_SUCCESS'", orderNo))
                .containsEntry("from_status", 10).containsEntry("to_status", 20).containsEntry("operator_type", "SYSTEM");
    }

    /** The same event redelivered (MQ at-least-once): blocked by L3 consumer dedup */
    @Test
    void sameEventTwice_dedupedByConsumedEvent() {
        long orderNo = placeOrder();
        PaySuccessEvent e = paid(orderNo, 9900);
        assertThat(service.handle(e)).isTrue();
        assertThat(service.handle(e)).isFalse();
        assertThat(status(orderNo)).isEqualTo(20);
        assertThat(logs(orderNo, "PAY_SUCCESS")).isEqualTo(1);
    }

    /** Two payments for the same order both succeed (different eventIds, dedup can't stop it): blocked by the L4 order CAS, the order transitions only once */
    @Test
    void twoPaymentsForSameOrder_secondRejectedByCas() {
        long orderNo = placeOrder();
        assertThat(service.handle(paid(orderNo, 9900))).isTrue();
        assertThat(service.handle(paid(orderNo, 9900))).isFalse();
        assertThat(status(orderNo)).isEqualTo(20);
        // Bug caught in code review: the log used to be inserted before the CAS check, so the second, failed transition also left a PAY_SUCCESS entry
        assertThat(logs(orderNo, "PAY_SUCCESS")).isEqualTo(1);
    }

    /** A closed order (e.g. money arrives after timeout close) can't be "revived" by a payment event */
    @Test
    void closedOrder_notRevived() {
        long orderNo = placeOrder();
        jdbc.update("UPDATE orders SET status = 50 WHERE order_no = ?", orderNo);
        assertThat(service.handle(paid(orderNo, 9900))).isFalse();
        assertThat(status(orderNo)).isEqualTo(50);
        // Must never have "order closed, but the log says paid"
        assertThat(logs(orderNo, "PAY_SUCCESS")).isZero();
    }

    /**
     * Destructive: success events from 8 different payments hit the same order at once.
     * Conclusion: the CAS lets only one succeed. With "SELECT to check status == 10, then UPDATE", all 8 threads would read 10 and each think it won.
     */
    @Test
    void concurrentPayEvents_exactlyOneWins() throws Exception {
        long orderNo = placeOrder();
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            PaySuccessEvent e = paid(orderNo, 9900);
            results.add(pool.submit(() -> {
                start.await();
                // Worker threads lack the test thread's context: set it from the message's merchant, like the real consumer
                return TenantContext.callAs(TenantInfo.systemOf(TENANT), () -> service.handle(e));
            }));
        }
        start.countDown();
        int wins = 0;
        for (Future<Boolean> f : results) {
            wins += f.get() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
        assertThat(status(orderNo)).isEqualTo(20);
        assertThat(logs(orderNo, "PAY_SUCCESS")).isEqualTo(1);
    }

    /** The event's merchant / amount doesn't match the order: no transition */
    @Test
    void tenantOrAmountMismatch_rejected() {
        long orderNo = placeOrder();
        long payNo = PAY_NOS.incrementAndGet();
        assertThat(service.handle(new PaySuccessEvent(payNo, payNo, orderNo, 999L, BUYER, 9900, "T" + payNo, LocalDateTime.now())))
                .isFalse();
        assertThat(service.handle(paid(orderNo, 1))).isFalse();
        assertThat(status(orderNo)).isEqualTo(10);
    }

    /** MANDATORY: calling outside a transaction fails immediately, preventing anyone from bypassing the transaction and leaving "status changed, log not written" */
    @Test
    void transitWithoutTransaction_rejected() {
        long orderNo = placeOrder();
        assertThatThrownBy(() -> transitioner.transit(orderNo, TENANT, OrderEvent.PAY_SUCCESS, "SYSTEM", 0, null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(status(orderNo)).isEqualTo(10);
    }
}
