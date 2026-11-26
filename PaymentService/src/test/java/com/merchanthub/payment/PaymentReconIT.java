package com.merchanthub.payment;

import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import com.merchanthub.payment.client.OrderClient;
import com.merchanthub.payment.client.dto.PayCheckItem;
import com.merchanthub.payment.client.dto.PayCheckResult;
import com.merchanthub.payment.job.PaymentJobs;
import com.merchanthub.payment.job.PaymentReconciler;
import com.merchanthub.payment.model.dto.MockCallbackRequest;
import com.merchanthub.payment.mq.PaymentEventTopics;
import com.merchanthub.payment.service.PaymentService;
import com.merchanthub.testsupport.IntegrationTestBase;
import com.merchanthub.testsupport.SharedContainers;
import feign.FeignException;
import feign.Request;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Payment-side reconciliation, one test case per kind of inconsistency. order is a Mockito stub (returns pay-check / statuses results per scenario);
 * re-sent events are caught by an observer consumer on a real RocketMQ.
 * <p>
 * What the order side does with a re-sent event (pending payment → paid; closed → record PAID_AFTER_CLOSED) is covered by order's OrderPaidIT / PayTimeoutRaceIT,
 * and flagging pay_anomaly for refund is covered by OrderReconIT — together the three pieces form the full reconciliation loop.
 */
@SpringBootTest
class PaymentReconIT extends IntegrationTestBase {

    private static final long TENANT = 7L;
    private static final AtomicLong NOS = new AtomicLong(System.currentTimeMillis() * 1000);
    private static final Map<Long, List<MessageExt>> EVENTS = new ConcurrentHashMap<>();
    private static DefaultMQPushConsumer observer;

    @MockitoBean
    private OrderClient orderClient;
    @Autowired
    private PaymentReconciler reconciler;
    @Autowired
    private PaymentJobs jobs;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private JdbcTemplate jdbc;

    private TenantContext.Scope scope;

    @BeforeAll
    static void startObserver() throws Exception {
        SharedContainers.rocketmq().createTopic(PaymentEventTopics.TOPIC);
        observer = new DefaultMQPushConsumer("it-recon-observer");
        observer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        observer.setVipChannelEnabled(false);
        observer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        observer.subscribe(PaymentEventTopics.TOPIC, "*");
        observer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            for (MessageExt m : msgs) {
                long payNo = Long.parseLong(m.getKeys().split(" ")[1]);
                EVENTS.computeIfAbsent(payNo, k -> new CopyOnWriteArrayList<>()).add(m);
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        observer.start();
    }

    @AfterAll
    static void stopObserver() {
        observer.shutdown();
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE payment");
        jdbc.execute("TRUNCATE TABLE recon_exception");
        scope = TenantContext.open(TenantInfo.system());
    }

    @AfterEach
    void close() {
        scope.close();
    }

    /** Create a payment: status 0 pending / 1 succeeded; ageMinutes = how many minutes ago it was created (and succeeded) */
    private long payment(int status, long orderNo, int ageMinutes, int reconRetry) {
        long payNo = NOS.incrementAndGet();
        jdbc.update("INSERT INTO payment (tenant_id, pay_no, order_no, buyer_id, amount, channel, status, channel_trade_no,"
                        + " paid_at, recon_retry, created_at) VALUES (?, ?, ?, 1001, 9900, 'MOCK', ?, ?,"
                        + " IF(? = 1, UTC_TIMESTAMP(3) - INTERVAL ? MINUTE, NULL), ?, UTC_TIMESTAMP(3) - INTERVAL ? MINUTE)",
                TENANT, payNo, orderNo, status, status == 1 ? "CH" + payNo : null, status, ageMinutes, reconRetry, ageMinutes);
        return payNo;
    }

    private Map<String, Object> row(long payNo) {
        return jdbc.queryForMap("SELECT status, recon_status, recon_retry FROM payment WHERE pay_no = ?", payNo);
    }

    private static PayCheckResult check(long payNo, long orderNo, Integer orderStatus, boolean processed) {
        return new PayCheckResult(payNo, orderNo, orderStatus, processed);
    }

    /**
     * Reconcile four kinds of "payment succeeded" records in one run:
     * <ul>
     *   <li>A: order already processed it → mark consistent, no re-send</li>
     *   <li>B: <b>payment succeeded but the order is still pending payment</b> (event lost) → re-send the same event (eventId = payNo); after order consumes it the order becomes paid</li>
     *   <li>C: payment succeeded but the order does not exist → no re-send, record a PAY_MISMATCH alert for manual handling</li>
     *   <li>D: re-sent 3 times and order still hasn't processed it → stop re-sending, record a PAY_RESEND_EXHAUSTED alert</li>
     *   <li>E: succeeded just 1 minute ago (within the grace period) → the event may still be in flight, skip it this round</li>
     * </ul>
     */
    @Test
    void successPayments_reconciled_resent_orAlerted() {
        long orderA = NOS.incrementAndGet(), orderB = NOS.incrementAndGet(), orderC = NOS.incrementAndGet(), orderD = NOS.incrementAndGet();
        long a = payment(1, orderA, 10, 0);
        long b = payment(1, orderB, 10, 0);
        long c = payment(1, orderC, 10, 0);
        long d = payment(1, orderD, 10, 3);
        long fresh = payment(1, NOS.incrementAndGet(), 1, 0);
        when(orderClient.payCheck(any())).thenAnswer(inv -> {
            List<PayCheckItem> items = inv.getArgument(0);
            assertThat(items).extracting(PayCheckItem::payNo).doesNotContain(fresh);
            return ResultUtils.success(List.of(
                    check(a, orderA, 20, true), check(b, orderB, 10, false),
                    check(c, orderC, null, false), check(d, orderD, 10, false)));
        });

        jobs.recon();

        assertThat(row(a)).containsEntry("recon_status", 1);
        assertThat(row(b)).containsEntry("recon_status", 0).containsEntry("recon_retry", 1);
        assertThat(row(c)).containsEntry("recon_status", 3);
        assertThat(row(d)).containsEntry("recon_status", 3);
        assertThat(row(fresh)).containsEntry("recon_status", 0).containsEntry("recon_retry", 0);
        assertThat(jdbc.queryForList("SELECT type FROM recon_exception ORDER BY biz_key", String.class))
                .containsExactlyInAnyOrder("PAY_MISMATCH", "PAY_RESEND_EXHAUSTED");

        // B's re-sent event really reached MQ: eventId is payNo, and order's consume-dedup table is idempotent on it
        await().atMost(Duration.ofSeconds(30)).until(() -> EVENTS.containsKey(b));
        MessageExt m = EVENTS.get(b).get(0);
        assertThat(m.getUserProperty(PaymentEventTopics.PROP_EVENT_ID)).isEqualTo(String.valueOf(b));
        assertThat(new String(m.getBody(), StandardCharsets.UTF_8)).contains("\"orderNo\":" + orderB);
        assertThat(EVENTS).doesNotContainKeys(a, c, d);

        // Next round, order has processed it → mark consistent
        // Use doReturn: the when(mock.payCheck(..)) form would first really invoke the answer above (with null arguments)
        doReturn(ResultUtils.success(List.of(check(b, orderB, 20, true)))).when(orderClient).payCheck(any());
        PaymentReconciler.Result r = reconciler.reconcile();
        assertThat(r.consistent()).isEqualTo(1);
        assertThat(row(b)).containsEntry("recon_status", 1);
    }

    /** order unavailable: skip the whole round, neither re-send nor alert — "can't look it up" ≠ "not processed" */
    @Test
    void orderDown_skipsRound() {
        long p = payment(1, NOS.incrementAndGet(), 10, 0);
        when(orderClient.payCheck(any())).thenThrow(new FeignException.ServiceUnavailable("down",
                Request.create(Request.HttpMethod.POST, "/x", Map.of(), null, StandardCharsets.UTF_8, null), null, null));
        when(orderClient.statuses(any())).thenThrow(new FeignException.ServiceUnavailable("down",
                Request.create(Request.HttpMethod.POST, "/x", Map.of(), null, StandardCharsets.UTF_8, null), null, null));
        payment(0, NOS.incrementAndGet(), 60, 0);

        PaymentReconciler.Result r = reconciler.reconcile();

        assertThat(r).isEqualTo(new PaymentReconciler.Result(0, 0, 0, 0));
        assertThat(row(p)).containsEntry("recon_status", 0).containsEntry("recon_retry", 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recon_exception", Long.class)).isZero();
    }

    /**
     * <b>Payments left open when the order was closed</b>: order closed (or paid by another payment / missing) → close it; order still pending payment → leave it;
     * pending payments that are too new (the buyer may be paying right now) → not checked.
     * Money arrives after closing → the callback recognizes "closed" and records a PAID_AFTER_CLOSED alert for refund
     */
    @Test
    void stalePendingPayments_closedWhenOrderNoLongerPayable() {
        long closedOrder = NOS.incrementAndGet(), paidOrder = NOS.incrementAndGet(), pendingOrder = NOS.incrementAndGet();
        long missingOrder = NOS.incrementAndGet();
        long p1 = payment(0, closedOrder, 60, 0);
        long p2 = payment(0, paidOrder, 60, 0);
        long p3 = payment(0, pendingOrder, 60, 0);
        long p4 = payment(0, missingOrder, 60, 0);
        long p5 = payment(0, NOS.incrementAndGet(), 5, 0);
        when(orderClient.statuses(any())).thenReturn(ResultUtils.success(Map.of(closedOrder, 50, paidOrder, 20, pendingOrder, 10)));

        PaymentReconciler.Result r = reconciler.reconcile();

        assertThat(r.closedPayments()).isEqualTo(3);
        assertThat(row(p1)).containsEntry("status", 3);
        assertThat(row(p2)).containsEntry("status", 3);
        assertThat(row(p3)).containsEntry("status", 0);
        assertThat(row(p4)).containsEntry("status", 3);
        assertThat(row(p5)).containsEntry("status", 0);
        verify(orderClient, never()).payCheck(any());

        // The buyer still paid on the old payment page: money arrived but the payment is closed → no payment-success event, record an anomaly for refund
        PaymentService.CallbackOutcome outcome = paymentService.handleMockCallback(new MockCallbackRequest(p1, "CH-LATE", 9900L));
        assertThat(outcome).isEqualTo(PaymentService.CallbackOutcome.IGNORED);
        assertThat(jdbc.queryForObject("SELECT type FROM recon_exception WHERE biz_key = ?", String.class, "payNo:" + p1))
                .isEqualTo("PAID_AFTER_CLOSED");
    }
}
