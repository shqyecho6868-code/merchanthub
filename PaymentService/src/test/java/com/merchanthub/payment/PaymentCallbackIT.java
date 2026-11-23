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
 * 支付单 + 回调幂等（B3）。真实 MySQL / Redis / RocketMQ；order 用 Mockito 替身。
 * 用一个"旁观者"消费者统计 PAYMENT_EVENT 上每张支付单收到几条事件 —— 回调幂等的最终标准是"事件只发一条"。
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

    /** C1：测试线程直接调 service（绕过 Controller），默认给 SYSTEM 身份；租户插件 fail-closed，不给会直接报错 */
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
     * 断言"只有一个逻辑事件"：收到的消息全部来自赢得 CAS 的那次回调（尝试 ID 等于支付单上的记录），eventId 都等于 payNo。
     * 不断言"恰好 1 条消息"：COMMIT 是 oneway 的，和 Broker 回查有竞态，赢家的消息可能被提交两次（P19 实测）；
     * RocketMQ 本就不承诺恰好一次，同一 eventId 的重复由 order 消费端去重（第三层）。
     */
    private void assertExactlyOneEvent(long payNo) {
        await().atMost(Duration.ofSeconds(30)).until(() -> EVENTS.getOrDefault(payNo, List.of()).size() >= 1);
        // 测试 Broker 3 秒起回查：多等一会儿，让被误提交的输家消息（如果有）也能到达
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
        // 一个订单可以建多张支付单（多次支付尝试）
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

    /** 先后到达的 3 次重复回调：第 1 次 CAS 赢，后 2 次被第一层快速路径挡住；事件只有 1 条 */
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
     * 破坏性：10 次重复回调同时到达（都读到"待支付"，快速路径挡不住）。
     * 结论：第二层 CAS 只让 1 次成功，其余 9 次的半消息被回滚，事件只有 1 条。
     * 踩坑（P19）：9 条回滚的半消息实测仍会被 Broker 回查；回查最初只看"支付单已成功"，把它们全提交了，收到 11 条事件。
     * 改为比对赢家尝试 ID 后，输家的消息不再被提交（赢家自己的消息仍可能被重复投递，见 assertExactlyOneEvent）。
     * 如果把 CAS 换成"读到待支付就 UPDATE SET status = 1"（不带 WHERE status = 0），10 次都会认为自己成功、发 10 条事件。
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
                // 和 Controller 一样以 SYSTEM 身份处理回调；子线程要自己设上下文（C1）
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

    /** 事务回查：只看数据库里的支付单 */
    @Test
    void txCheck_decidesByPaymentRow() {
        long ok = newPayment();
        service.handleMockCallback(new MockCallbackRequest(ok, "K-" + ok, 9900L));
        String winner = jdbc.queryForObject("SELECT success_attempt FROM payment WHERE pay_no = ?", String.class, ok);
        MessageExt winnerMsg = halfMsg(ok, "K-" + ok, System.currentTimeMillis());
        winnerMsg.putUserProperty(PaymentEventTopics.PROP_ATTEMPT, winner);
        assertThat(txListener.checkLocalTransaction(winnerMsg)).isEqualTo(LocalTransactionState.COMMIT_MESSAGE);
        // 输掉 CAS 的重复回调的半消息：支付单已成功，但尝试 ID 不是它 → 不能提交（P19）
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
