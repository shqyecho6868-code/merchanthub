package com.merchanthub.order;

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
 * 支付成功 → 订单状态 CAS + 状态流水（B3）。直接调用消费处理方法，覆盖各种"重复 / 迟到 / 并发"的支付成功事件。
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

    /** 同一事件重投（MQ at-least-once）：第三层消费去重挡住 */
    @Test
    void sameEventTwice_dedupedByConsumedEvent() {
        long orderNo = placeOrder();
        PaySuccessEvent e = paid(orderNo, 9900);
        assertThat(service.handle(e)).isTrue();
        assertThat(service.handle(e)).isFalse();
        assertThat(status(orderNo)).isEqualTo(20);
        assertThat(logs(orderNo, "PAY_SUCCESS")).isEqualTo(1);
    }

    /** 同一订单两张支付单都付成功（eventId 不同，去重挡不住）：第四层订单 CAS 挡住，订单只流转一次 */
    @Test
    void twoPaymentsForSameOrder_secondRejectedByCas() {
        long orderNo = placeOrder();
        assertThat(service.handle(paid(orderNo, 9900))).isTrue();
        assertThat(service.handle(paid(orderNo, 9900))).isFalse();
        assertThat(status(orderNo)).isEqualTo(20);
        // 🔍 B3 审代码的 bug：流水曾在 CAS 判断之前插入，第二次失败的流转也会留下一条 PAY_SUCCESS
        assertThat(logs(orderNo, "PAY_SUCCESS")).isEqualTo(1);
    }

    /** 已关闭的订单（如超时关单后钱才到）不能被支付事件"复活" */
    @Test
    void closedOrder_notRevived() {
        long orderNo = placeOrder();
        jdbc.update("UPDATE orders SET status = 50 WHERE order_no = ?", orderNo);
        assertThat(service.handle(paid(orderNo, 9900))).isFalse();
        assertThat(status(orderNo)).isEqualTo(50);
        // 不能出现"订单已关闭、流水却写着已支付"
        assertThat(logs(orderNo, "PAY_SUCCESS")).isZero();
    }

    /**
     * 破坏性：8 个不同支付单的成功事件同时到达同一订单。
     * 结论：CAS 只让一个成功。如果改成"先 SELECT 判断 status == 10 再 UPDATE"，8 个线程都会读到 10，都认为自己赢了。
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
                return service.handle(e);
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

    /** 事件里的商家 / 金额和订单对不上：不流转 */
    @Test
    void tenantOrAmountMismatch_rejected() {
        long orderNo = placeOrder();
        long payNo = PAY_NOS.incrementAndGet();
        assertThat(service.handle(new PaySuccessEvent(payNo, payNo, orderNo, 999L, BUYER, 9900, "T" + payNo, LocalDateTime.now())))
                .isFalse();
        assertThat(service.handle(paid(orderNo, 1))).isFalse();
        assertThat(status(orderNo)).isEqualTo(10);
    }

    /** MANDATORY：脱离事务调用直接报错，防止有人绕过事务导致"状态改了流水没写" */
    @Test
    void transitWithoutTransaction_rejected() {
        long orderNo = placeOrder();
        assertThatThrownBy(() -> transitioner.transit(orderNo, TENANT, OrderEvent.PAY_SUCCESS, "SYSTEM", 0, null))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(status(orderNo)).isEqualTo(10);
    }
}
