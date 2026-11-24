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
 * E3：order 侧的 XXL-Job 任务。直接调任务入口（OrderJobs）/ 业务类，不起调度中心。
 * <ul>
 *   <li>超时订单扫描：定时关单消息没来，过了截止时间 + 宽限期仍是待支付 → 兜底关单，并写 ORDER_CLOSED（库存据此释放）</li>
 *   <li>pay_anomaly：PAID_AFTER_CLOSED 等标记待退款 + 告警，<b>订单状态不动</b></li>
 *   <li>outbox 投递失败（status=2）告警；已发送记录归档</li>
 *   <li>对账用的内部查询接口：pay-check（支付成功事件处理过没有）、statuses（批量状态）</li>
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
     * 场景：ORDER_TIMEOUT 定时消息丢了（outbox 标失败 / 进了死信），订单过期 1 小时仍是待支付、库存一直被占着。
     * 结论：扫描兜底关单 + 写 ORDER_CLOSED 事件；宽限期内的订单不碰；重复执行不会重复关单（CAS 幂等）
     */
    @Test
    void timeoutScan_closesExpiredPendingOrders_once() {
        long expired = newOrder();
        long inGrace = newOrder();
        jdbc.update("UPDATE orders SET expire_at = UTC_TIMESTAMP(3) - INTERVAL 1 HOUR WHERE order_no = ?", expired);
        jdbc.update("UPDATE orders SET expire_at = UTC_TIMESTAMP(3) - INTERVAL 1 MINUTE WHERE order_no = ?", inGrace);

        jobs.timeoutScan();

        assertThat(status(expired)).isEqualTo(50);
        assertThat(status(inGrace)).isEqualTo(10);   // 宽限期 5 分钟内：定时消息可能还在路上
        Map<String, Object> closed = jdbc.queryForMap(
                "SELECT payload FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'", String.valueOf(expired));
        assertThat((String) closed.get("payload")).contains("\"buyerId\": " + BUYER).contains("PAY_TIMEOUT");
        assertThat(count("SELECT COUNT(*) FROM order_status_log WHERE order_no = ? AND remark LIKE '%扫描兜底%'", expired))
                .isEqualTo(1);

        jobs.timeoutScan();   // 再跑一轮：订单已不是待支付，什么也不做
        assertThat(count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'", String.valueOf(expired)))
                .isEqualTo(1);
    }

    /**
     * 场景：订单超时关闭后，钱才到（PAID_AFTER_CLOSED），消费端已记 pay_anomaly。
     * 结论：对账把它标成"待退款"（handle_status=2）并告警；订单仍是已关闭 —— 不能复活，库存早已释放
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

        // 再跑一轮：已处理的不再告警
        assertThat(reconciler.reconcile().anomaliesMarked()).isZero();
        assertThat(count("SELECT COUNT(*) FROM recon_exception")).isEqualTo(3);
    }

    /** outbox 重试满上限（status=2）：告警一次，不重复刷屏 */
    @Test
    void failedOutbox_alertedOnce() {
        jdbc.update("INSERT INTO order_outbox (tenant_id, event_id, biz_key, topic, tag, payload, status, retry_count, next_retry_at, last_error)"
                + " VALUES (?, 1, '123', 'ORDER_EVENT', 'ORDER_PAID', '{}', 2, 10, UTC_TIMESTAMP(3), 'broker down')", TENANT);
        jobs.recon();
        assertThat(jdbc.queryForObject("SELECT type FROM recon_exception", String.class)).isEqualTo("OUTBOX_FAILED");
        assertThat(reconciler.reconcile().outboxFailedAlerted()).isZero();
    }

    /** 归档：只搬"已发送且超过保留期"的；待发送（0）即使很老也必须留在主表 */
    @Test
    void archive_movesOnlyOldSentRows() {
        String sql = "INSERT INTO order_outbox (tenant_id, event_id, biz_key, topic, tag, payload, status, retry_count, next_retry_at, sent_at)"
                + " VALUES (?, ?, 'k', 'ORDER_EVENT', 'ORDER_PAID', '{}', ?, 0, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) - INTERVAL ? DAY)";
        jdbc.update(sql, TENANT, 11, 1, 10);   // 已发送 10 天 → 归档
        jdbc.update(sql, TENANT, 12, 1, 1);    // 已发送 1 天 → 保留
        jdbc.update(sql, TENANT, 13, 0, 10);   // 待发送 → 保留

        jobs.outboxArchive();

        assertThat(jdbc.queryForList("SELECT event_id FROM order_outbox ORDER BY event_id", Long.class)).containsExactly(12L, 13L);
        assertThat(jdbc.queryForList("SELECT event_id FROM order_outbox_archive", Long.class)).containsExactly(11L);
    }

    /** pay-check 按消费去重表回答"这张支付单的事件 order 处理过没有"；statuses 批量回状态 */
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
        jobs.outboxRelay();   // 没有到期记录也能正常结束
    }
}
