package com.merchanthub.order;

import com.merchanthub.order.controller.InternalOrderController;
import com.merchanthub.order.job.OrderJobs;
import com.merchanthub.order.job.OrderReconciler;
import com.merchanthub.order.model.dto.PayCheckItem;
import com.merchanthub.order.model.vo.PayCheckResult;
import com.merchanthub.order.mq.payment.OrderPaidService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3: order-side XXL-Job tasks. Calls the task entry points (OrderJobs) / business classes directly, without an admin server.
 * <ul>
 *   <li>Timeout-order scan: the scheduled close message never came and the order is still pending past deadline + grace period -> fallback close, writing ORDER_CLOSED (inventory released from it)</li>
 *   <li>pay_anomaly: PAID_AFTER_CLOSED etc. marked pending refund + alert, <b>order status untouched</b></li>
 *   <li>outbox delivery failures (status=2) alerted; sent records archived</li>
 *   <li>Internal query APIs for reconciliation: pay-check (was the payment-success event processed), statuses (batch status)</li>
 * </ul>
 */
@SpringBootTest
class OrderReconIT extends OrderItSupport {

    @Autowired
    private OrderJobs jobs;
    @Autowired
    private OrderReconciler reconciler;
    @Autowired
    private InternalOrderController internal;

    private long newOrder() {
        return Long.parseLong(orderCreateService.createOrder(BUYER, req(null, 101, 1)).getOrderNo());
    }

    private int status(long orderNo) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    /**
     * Scenario: the ORDER_TIMEOUT scheduled message was lost (outbox marked failed / sent to the DLQ); the order is still pending 1 hour past expiry with inventory held.
     * Conclusion: the scan closes it as a fallback + writes the ORDER_CLOSED event; orders within the grace period are untouched; reruns don't close twice (CAS is idempotent)
     */
    @Test
    void timeoutScan_closesExpiredPendingOrders_once() {
        long expired = newOrder();
        long inGrace = newOrder();
        jdbc.update("UPDATE orders SET expire_at = UTC_TIMESTAMP(3) - INTERVAL 1 HOUR WHERE order_no = ?", expired);
        jdbc.update("UPDATE orders SET expire_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE WHERE order_no = ?", inGrace);

        jobs.timeoutScan();

        assertThat(status(expired)).isEqualTo(50);
        assertThat(status(inGrace)).isEqualTo(10);   // within the 5-minute grace period: the scheduled message may still be in flight
        Map<String, Object> closed = jdbc.queryForMap(
                "SELECT payload FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'", String.valueOf(expired));
        assertThat((String) closed.get("payload")).contains("\"buyerId\": " + BUYER).contains("PAY_TIMEOUT");
        assertThat(count("SELECT COUNT(*) FROM order_status_log WHERE order_no = ? AND remark LIKE '%扫描兜底%'", expired))
                .isEqualTo(1);

        jobs.timeoutScan();   // another round: the order is no longer pending, nothing happens
        assertThat(count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'", String.valueOf(expired)))
                .isEqualTo(1);
    }

    /**
     * Scenario: money arrives after the order was closed for timeout (PAID_AFTER_CLOSED); the consumer already recorded pay_anomaly.
     * Conclusion: reconciliation marks it "pending refund" (handle_status=2) and alerts; the order stays closed -- it can't be revived, inventory was released long ago
     */
    @Test
    void payAnomaly_markedRefundPending_orderUntouched() {
        long orderNo = newOrder();
        jdbc.update("UPDATE orders SET status = 50 WHERE order_no = ?", orderNo);
        jdbc.update("INSERT INTO pay_anomaly (tenant_id, pay_no, order_no, amount, type, order_status, handle_status)"
                + " VALUES (?, 900001, ?, 9900, 'PAID_AFTER_CLOSED', 50, 0)", TENANT, orderNo);
        jdbc.update("INSERT INTO pay_anomaly (tenant_id, pay_no, order_no, amount, type, order_status, handle_status)"
                + " VALUES (?, 900002, ?, 9900, 'DUPLICATE_PAYMENT', 20, 0)", TENANT, orderNo);
        jdbc.update("INSERT INTO pay_anomaly (tenant_id, pay_no, order_no, amount, type, order_status, handle_status)"
                + " VALUES (?, 900003, ?, 1, 'MISMATCH', 10, 0)", TENANT, orderNo);

        OrderReconciler.Result r = reconciler.reconcile();

        assertThat(r.anomaliesMarked()).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM pay_anomaly WHERE handle_status = 2")).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT type FROM recon_exception ORDER BY biz_key", String.class))
                .containsExactly("PAID_AFTER_CLOSED", "DUPLICATE_PAYMENT", "PAY_MISMATCH");
        assertThat(status(orderNo)).isEqualTo(50);

        // Another round: already-handled ones aren't alerted again
        assertThat(reconciler.reconcile().anomaliesMarked()).isZero();
        assertThat(count("SELECT COUNT(*) FROM recon_exception")).isEqualTo(3);
    }

    /** outbox retries hit the limit (status=2): alert once, without flooding */
    @Test
    void failedOutbox_alertedOnce() {
        jdbc.update("INSERT INTO order_outbox (tenant_id, event_id, biz_key, topic, tag, payload, status, retry_count, next_retry_at, last_error)"
                + " VALUES (?, 1, '123', 'ORDER_EVENT', 'ORDER_PAID', '{}', 2, 10, UTC_TIMESTAMP(3), 'broker down')", TENANT);
        jobs.recon();
        assertThat(jdbc.queryForObject("SELECT type FROM recon_exception", String.class)).isEqualTo("OUTBOX_FAILED");
        assertThat(reconciler.reconcile().outboxFailedAlerted()).isZero();
    }

    /** Archiving: only moves "sent and past retention"; pending (0) must stay in the main table even if very old */
    @Test
    void archive_movesOnlyOldSentRows() {
        String sql = "INSERT INTO order_outbox (tenant_id, event_id, biz_key, topic, tag, payload, status, retry_count, next_retry_at, sent_at)"
                + " VALUES (?, ?, 'k', 'ORDER_EVENT', 'ORDER_PAID', '{}', ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) - INTERVAL ? DAY)";
        jdbc.update(sql, TENANT, 11, 1, 10);   // sent 10 days ago -> archived
        jdbc.update(sql, TENANT, 12, 1, 1);    // sent 1 day ago -> kept
        jdbc.update(sql, TENANT, 13, 0, 10);   // pending -> kept

        jobs.outboxArchive();

        assertThat(jdbc.queryForList("SELECT event_id FROM order_outbox ORDER BY event_id", Long.class)).containsExactly(12L, 13L);
        assertThat(jdbc.queryForList("SELECT event_id FROM order_outbox_archive", Long.class)).containsExactly(11L);
    }

    /** pay-check answers "has order processed this payment's event" from the consumer dedup table; statuses returns statuses in batch */
    @Test
    void internalQueries_forReconciliation() {
        long orderNo = newOrder();
        jdbc.update("INSERT INTO consumed_event (tenant_id, consumer_group, event_id) VALUES (?, ?, 777)",
                TENANT, OrderPaidService.CONSUMER_GROUP);

        List<PayCheckResult> r = internal.payCheck(List.of(
                new PayCheckItem(777, orderNo), new PayCheckItem(778, orderNo), new PayCheckItem(779, 1L))).getData();
        assertThat(r).containsExactly(
                new PayCheckResult(777, orderNo, 10, true),
                new PayCheckResult(778, orderNo, 10, false),
                new PayCheckResult(779, 1L, null, false));

        assertThat(internal.statuses(List.of(orderNo, 1L)).getData()).containsExactly(Map.entry(orderNo, 10));
        assertThat(internal.statuses(List.of()).getData()).isEmpty();
        assertThat(internal.payCheck(List.of()).getData()).isEmpty();
    }

    @Test
    void outboxRelayJob_runs() {
        jobs.outboxRelay();   // finishes normally even with no due records
    }
}
