package com.merchanthub.payment;

import com.merchanthub.common.tenant.TenantContext;
import com.merchanthub.common.tenant.TenantInfo;
import org.junit.jupiter.api.AfterEach;

import com.merchanthub.common.common.ErrorCode;
import com.merchanthub.common.common.ResultUtils;
import com.merchanthub.common.exception.BusinessException;
import com.merchanthub.payment.client.OrderClient;
import com.merchanthub.payment.client.dto.OrderBrief;
import com.merchanthub.payment.model.dto.MockCallbackRequest;
import com.merchanthub.payment.mq.PaymentEventTopics;
import com.merchanthub.payment.mq.PaymentTxListener;
import com.merchanthub.payment.service.PaymentService;
import com.merchanthub.payment.service.PaymentService.CallbackOutcome;
import com.merchanthub.testsupport.IntegrationTestBase;
import com.merchanthub.testsupport.SharedContainers;
import feign.FeignException;
import feign.Request;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * Payment + callback idempotency. Real MySQL / Redis / RocketMQ; order is a Mockito stub.
 * An "observer" consumer counts how many events each payment gets on PAYMENT_EVENT — the ultimate test of callback idempotency is "only one event is sent".
 */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentCallbackIT extends IntegrationTestBase {

    private static final long TENANT = 7L;
    private static final long BUYER = 1001L;
    private static final AtomicLong ORDER_NOS = new AtomicLong(System.currentTimeMillis() * 1000);
    private static final Map<Long, List<MessageExt>> EVENTS = new ConcurrentHashMap<>();
    private static DefaultMQPushConsumer observer;

    @MockitoBean
    private OrderClient orderClient;
    @Autowired
    private PaymentService service;
    @Autowired
    private PaymentTxListener txListener;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void startObserver() throws Exception {
        SharedContainers.rocketmq().createTopic(PaymentEventTopics.TOPIC);
        observer = new DefaultMQPushConsumer("it-pay-observer");
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

    private TenantContext.Scope tenantScope;

    /** The test thread calls the service directly (bypassing the Controller), so give it a SYSTEM identity by default; the tenant plugin is fail-closed and errors out without one */
    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE payment");
        tenantScope = TenantContext.open(TenantInfo.system());
    }

    @AfterEach
    void closeTenantScope() {
        tenantScope.close();
    }

    private long pendingOrder() {
        long orderNo = ORDER_NOS.incrementAndGet();
        OrderBrief o = new OrderBrief();
        o.setOrderNo(orderNo);
        o.setTenantId(TENANT);
        o.setBuyerId(BUYER);
        o.setStatus(OrderBrief.PENDING_PAYMENT);
        o.setPayAmount(9900L);
        when(orderClient.getOrder(orderNo)).thenReturn(ResultUtils.success(o));
        return orderNo;
    }

    private long newPayment() {
        return service.create(BUYER, pendingOrder()).payNo();
    }

    private int status(long payNo) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE pay_no = ?", Integer.class, payNo);
    }

    /**
     * Asserts "only one logical event": every received message comes from the callback that won the CAS (attempt ID equals the one recorded on the payment), and every eventId equals payNo.
     * Does not assert "exactly 1 message": COMMIT is oneway and races with the Broker's check-back, so the winner's message may be committed twice (observed in tests);
     * RocketMQ never promised exactly-once anyway; duplicates with the same eventId are deduplicated by the order consumer (the third layer).
     */
    private void assertExactlyOneEvent(long payNo) {
        await().atMost(Duration.ofSeconds(30)).until(() -> EVENTS.getOrDefault(payNo, List.of()).size() >= 1);
        // The test Broker starts checking back after 3 seconds: wait a bit longer so any wrongly committed loser messages also arrive
        await().pollDelay(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(10)).until(() -> true);
        String winner = jdbc.queryForObject("SELECT success_attempt FROM payment WHERE pay_no = ?", String.class, payNo);
        assertThat(EVENTS.get(payNo)).isNotEmpty().allSatisfy(m -> {
            assertThat(m.getUserProperty(PaymentEventTopics.PROP_ATTEMPT)).isEqualTo(winner);
            assertThat(m.getUserProperty(PaymentEventTopics.PROP_EVENT_ID)).isEqualTo(String.valueOf(payNo));
        });
    }

    @Test
    void create_amountFromOrder() {
        long orderNo = pendingOrder();
        var vo = service.create(BUYER, orderNo);
        assertThat(vo.amount()).isEqualTo(9900);
        assertThat(status(vo.payNo())).isZero();
        // One order can have multiple payments (multiple payment attempts)
        assertThat(service.create(BUYER, orderNo).payNo()).isNotEqualTo(vo.payNo());
    }

    @Test
    void create_rejectsOthersOrder_nonPending_andDownstreamFailure() {
        long orderNo = pendingOrder();
        assertThatThrownBy(() -> service.create(2002L, orderNo)).isInstanceOf(BusinessException.class);

        long paidOrder = pendingOrder();
        when(orderClient.getOrder(paidOrder)).thenAnswer(inv -> {
            OrderBrief o = new OrderBrief();
            o.setOrderNo(paidOrder);
            o.setTenantId(TENANT);
            o.setBuyerId(BUYER);
            o.setStatus(20);
            o.setPayAmount(9900L);
            return ResultUtils.success(o);
        });
        assertThatThrownBy(() -> service.create(BUYER, paidOrder))
                .hasFieldOrPropertyWithValue("code", ErrorCode.ORDER_NOT_PAYABLE.getCode());

        when(orderClient.getOrder(anyLong())).thenThrow(new FeignException.ServiceUnavailable("down",
                Request.create(Request.HttpMethod.GET, "/x", Map.of(), null, StandardCharsets.UTF_8, null), null, null));
        assertThatThrownBy(() -> service.create(BUYER, 1L))
                .hasFieldOrPropertyWithValue("code", ErrorCode.DOWNSTREAM_UNAVAILABLE.getCode());
    }

    /** 3 duplicate callbacks arriving one after another: the 1st wins the CAS, the next 2 are stopped by the first-layer fast path; only 1 event */
    @Test
    void sequentialDuplicateCallbacks_onlyFirstTakesEffect() {
        long payNo = newPayment();
        var req = new MockCallbackRequest(payNo, "T-" + payNo, 9900L);
        assertThat(service.handleMockCallback(req)).isEqualTo(CallbackOutcome.PAID);
        assertThat(service.handleMockCallback(req)).isEqualTo(CallbackOutcome.DUPLICATE);
        assertThat(service.handleMockCallback(req)).isEqualTo(CallbackOutcome.DUPLICATE);

        assertThat(status(payNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT channel_trade_no FROM payment WHERE pay_no = ?", String.class, payNo))
                .isEqualTo("T-" + payNo);
        assertExactlyOneEvent(payNo);
    }

    /**
     * Destructive: 10 duplicate callbacks arrive at the same time (all read "pending payment", so the fast path can't stop them).
     * Conclusion: the second-layer CAS lets only 1 succeed; the other 9 half messages are rolled back, and there is only 1 event.
     * Pitfall: the 9 rolled-back half messages were still checked back by the Broker; the check-back originally only looked at "payment succeeded" and committed them all, yielding 11 events.
     * After switching to comparing the winner's attempt ID, the losers' messages are no longer committed (the winner's own message may still be redelivered, see assertExactlyOneEvent).
     * If the CAS were replaced by "read pending, then UPDATE SET status = 1" (without WHERE status = 0), all 10 would think they succeeded and send 10 events.
     */
    @Test
    void concurrentDuplicateCallbacks_exactlyOneWins() throws Exception {
        long payNo = newPayment();
        var req = new MockCallbackRequest(payNo, "T-" + payNo, 9900L);
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CallbackOutcome>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                // Handle the callback as SYSTEM, same as the Controller; a child thread must set its own context
                return TenantContext.callAsSystem(() -> service.handleMockCallback(req));
            }));
        }
        start.countDown();
        int paid = 0;
        for (Future<CallbackOutcome> f : fs) {
            paid += f.get() == CallbackOutcome.PAID ? 1 : 0;
        }
        pool.shutdown();
        assertThat(paid).isEqualTo(1);
        assertExactlyOneEvent(payNo);
    }

    @Test
    void amountMismatch_rejected_paymentStaysPending() {
        long payNo = newPayment();
        assertThatThrownBy(() -> service.handleMockCallback(new MockCallbackRequest(payNo, "T-x" + payNo, 1L)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.PAY_AMOUNT_MISMATCH.getCode());
        assertThat(status(payNo)).isZero();
    }

    @Test
    void channelTradeNoReusedByAnotherPayment_rejected() {
        long p1 = newPayment();
        long p2 = newPayment();
        service.handleMockCallback(new MockCallbackRequest(p1, "SAME-" + p1, 9900L));
        assertThatThrownBy(() -> service.handleMockCallback(new MockCallbackRequest(p2, "SAME-" + p1, 9900L)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.CHANNEL_TRADE_NO_CONFLICT.getCode());
        assertThat(status(p2)).isZero();
    }

    @Test
    void alreadySucceeded_differentTradeNo_or_closed_noNewEvent() {
        long payNo = newPayment();
        service.handleMockCallback(new MockCallbackRequest(payNo, "A-" + payNo, 9900L));
        assertThat(service.handleMockCallback(new MockCallbackRequest(payNo, "B-" + payNo, 9900L)))
                .isEqualTo(CallbackOutcome.DUPLICATE);

        long closed = newPayment();
        jdbc.update("UPDATE payment SET status = 3 WHERE pay_no = ?", closed);
        assertThat(service.handleMockCallback(new MockCallbackRequest(closed, "C-" + closed, 9900L)))
                .isEqualTo(CallbackOutcome.IGNORED);
        assertThatThrownBy(() -> service.handleMockCallback(new MockCallbackRequest(1L, "x", 1L)))
                .hasFieldOrPropertyWithValue("code", ErrorCode.PAYMENT_NOT_FOUND.getCode());
    }

    /** Transaction check-back: looks only at the payment in the database */
    @Test
    void txCheck_decidesByPaymentRow() {
        long ok = newPayment();
        service.handleMockCallback(new MockCallbackRequest(ok, "K-" + ok, 9900L));
        String winner = jdbc.queryForObject("SELECT success_attempt FROM payment WHERE pay_no = ?", String.class, ok);
        MessageExt winnerMsg = halfMsg(ok, "K-" + ok, System.currentTimeMillis());
        winnerMsg.putUserProperty(PaymentEventTopics.PROP_ATTEMPT, winner);
        assertThat(txListener.checkLocalTransaction(winnerMsg)).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        // Half message of a duplicate callback that lost the CAS: the payment has succeeded, but the attempt ID isn't this one → must not commit
        MessageExt loserMsg = halfMsg(ok, "K-" + ok, System.currentTimeMillis());
        loserMsg.putUserProperty(PaymentEventTopics.PROP_ATTEMPT, "loser");
        assertThat(txListener.checkLocalTransaction(loserMsg)).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);

        long pending = newPayment();
        assertThat(txListener.checkLocalTransaction(halfMsg(pending, "P-" + pending, System.currentTimeMillis())))
                .isEqualTo(LocalTransactionState.UNKNOW);
        assertThat(txListener.checkLocalTransaction(halfMsg(pending, "P-" + pending, System.currentTimeMillis() - 120_000)))
                .isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);

        MessageExt bad = new MessageExt();
        bad.setBody("not json".getBytes(StandardCharsets.UTF_8));
        assertThat(txListener.checkLocalTransaction(bad)).isEqualTo(LocalTransactionState.ROLLBACK_MESSAGE);
    }

    @Test
    void http_createAndCallback() throws Exception {
        long orderNo = pendingOrder();
        mvc.perform(post("/payments").header("X-User-Id", BUYER).header("X-Role", "BUYER").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":" + orderNo + "}"))
                .andExpect(jsonPath("$.data.amount").value(9900));
        long payNo = jdbc.queryForObject("SELECT pay_no FROM payment WHERE order_no = ?", Long.class, orderNo);
        String body = "{\"payNo\":" + payNo + ",\"channelTradeNo\":\"H-" + payNo + "\",\"amount\":9900}";
        mvc.perform(post("/payments/callback/mock").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.data").value("PAID"));
        mvc.perform(post("/payments/callback/mock").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.data").value("DUPLICATE"));
    }

    private MessageExt halfMsg(long payNo, String tradeNo, long bornAt) {
        String json = "{\"eventId\":" + payNo + ",\"payNo\":" + payNo + ",\"orderNo\":1,\"tenantId\":7,\"buyerId\":1,"
                + "\"amount\":9900,\"channelTradeNo\":\"" + tradeNo + "\",\"paidAt\":null}";
        MessageExt m = new MessageExt();
        m.setBody(json.getBytes(StandardCharsets.UTF_8));
        m.setBornTimestamp(bornAt);
        return m;
    }
}
