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
 * 破坏性实验 ②（自动化版）：同一订单的"支付成功"和"超时关单"同时到达。
 * <p>
 * 结论：订单 CAS（WHERE status = 10）让两者只有一个影响 1 行。
 * <ul>
 *   <li>支付赢：订单 20，只写 ORDER_PAID（库存确认），超时一方什么都不写</li>
 *   <li>超时赢：订单 50，只写 ORDER_CLOSED（库存释放），支付一方写一条 PAID_AFTER_CLOSED 支付异常留给对账</li>
 * </ul>
 * 任何订单都不会同时有 ORDER_PAID 和 ORDER_CLOSED —— 所以库存不可能"既确认又释放"。
 * 去掉 CAS 里的 {@code AND status = #{from}} 再跑，会看到同一订单两种事件都写了、流水 3 行。
 * <p>
 * 这个测试第一次跑就抓到一个 bug（pitfalls P22）：超时赢了，支付一方的异常记录却是 DUPLICATE_PAYMENT / 订单状态 10。
 * 原因是 CAS 失败后用普通 SELECT 读订单状态，可重复读下读到的是本事务开头的快照。改成 FOR UPDATE 当前读后通过。
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
            f.get();   // 任何一方抛异常（死锁等）都会在这里暴露
        }
        pool.shutdown();

        int paid = 0;
        int closed = 0;
        for (long orderNo : orderNos) {
            String key = String.valueOf(orderNo);
            int status = jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo);
            long paidEvents = count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_PAID'", key);
            long closedEvents = count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'", key);
            // 带 order_status = 50：记录里的"当时订单状态"也必须是最新的（P22：快照读会记成 10、类型误判成 DUPLICATE_PAYMENT）
            long anomalies = count("SELECT COUNT(*) FROM pay_anomaly WHERE order_no = ? AND type = 'PAID_AFTER_CLOSED' AND order_status = 50", orderNo);
            // 流水：CREATE + 恰好一次流转
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
                assertThat(anomalies).isEqualTo(1);   // 钱收了单没成：记下来留给对账
            }
        }
        assertThat(paid + closed).isEqualTo(orders);
        System.out.printf("支付与超时并发 %d 单：支付赢 %d，超时赢 %d%n", orders, paid, closed);
    }

    /** 先后版本：超时先关单，支付成功才到 → 订单不复活、不写 ORDER_PAID，只记支付异常 */
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

        // 重复的超时消息：去重表挡住
        assertThat(timeoutService.handle(new PayTimeoutMessage(payNo + 1, orderNo, TENANT))).isFalse();
    }

    /**
     * 先后版本（反方向）：支付先成功，超时消息照常到期投递。
     * <p>
     * 这是线上<b>最常见</b>的路径：定时消息发出后不会撤回，所以每个已支付订单都会收到一条超时消息。
     * 超时一方 CAS(10→50) 影响 0 行 → 订单保持已支付，不写流水、不写 ORDER_CLOSED（库存不会被释放）。
     * 去掉 CAS 里的 {@code AND status = #{from}} 再跑，会看到已支付订单被关掉、库存先确认又释放（B4 实验 ③）。
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
        // 流水：CREATE + PAY_SUCCESS，没有 PAY_TIMEOUT
        assertThat(count("SELECT COUNT(*) FROM order_status_log WHERE order_no = ?", orderNo)).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_PAID'", key)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_CLOSED'", key)).isZero();
        assertThat(count("SELECT COUNT(*) FROM pay_anomaly WHERE order_no = ?", orderNo)).isZero();
    }

    /**
     * 租户隔离：订单号对、租户不对的超时消息关不掉订单。
     * 订单 CAS 的 WHERE 里带 tenant_id，所以即使消息体被篡改 / 串租户，也只会影响 0 行。
     * 之后用正确租户的消息仍能正常关单（错误消息没有"占掉"任何东西）。
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
