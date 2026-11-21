package com.merchanthub.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchanthub.order.mq.payment.OrderNotVisibleException;
import com.merchanthub.order.mq.payment.OrderPaidService;
import com.merchanthub.order.mq.payment.PaySuccessEvent;
import com.merchanthub.order.service.NewOrder;
import com.merchanthub.order.service.OrderPlacer;
import com.merchanthub.order.service.OrderPricing;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 破坏性实验 ①（自动化版）：支付成功事件比订单先到。
 * <p>
 * 结论：订单不存在时抛 {@link OrderNotVisibleException} → 消费事务回滚（去重记录一起撤销）→ RocketMQ 重投 →
 * 订单落库后的某次重投成功，订单变成已支付。
 * 修复前（订单不存在时 return false）：去重记录提交、消息被确认，订单落库后永远收不到这次支付，最终被超时关单 —— 钱收了单没了。
 */
@SpringBootTest(properties = {
        "merchanthub.order.event-mode=OUTBOX",
        "merchanthub.order.pay-consumer.enabled=true",
        "merchanthub.order.pay-consumer.consume-from=CONSUME_FROM_FIRST_OFFSET"})
class PaySuccessBeforeOrderIT extends OrderItSupport {

    private static DefaultMQProducer producer;

    @Autowired
    private OrderPaidService paidService;
    @Autowired
    private OrderPlacer orderPlacer;
    @Autowired
    private ObjectMapper objectMapper;
    /** 应用的 UTC 时钟。不能用 LocalDateTime.now()：本机是 PST，expire_at 会落在 8 小时前 → 超时消息立即投递（P24） */
    @Autowired
    private Clock clock;

    @BeforeAll
    static void start() throws Exception {
        SharedContainers.rocketmq().createTopic(PaySuccessEvent.TOPIC);
        producer = new DefaultMQProducer("it-pay-early-producer");
        producer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        producer.setVipChannelEnabled(false);
        producer.start();
    }

    @AfterAll
    static void stop() {
        producer.shutdown();
    }

    /** 用指定订单号落一单（真实下单流程里订单号由服务端生成，测试需要提前知道它） */
    private void placeOrder(long orderNo) {
        Map<Long, Integer> qty = new TreeMap<>(Map.of(101L, 1));
        OrderPricing.Result pricing = OrderPricing.price(qty, catalog);
        LocalDateTime now = LocalDateTime.now(clock);
        orderPlacer.place(new NewOrder(orderNo, TENANT, BUYER, pricing, now, now.plusMinutes(15)));
    }

    private PaySuccessEvent event(long orderNo) {
        long payNo = System.nanoTime();
        return new PaySuccessEvent(payNo, payNo, orderNo, TENANT, BUYER, 9900, "T" + payNo, LocalDateTime.now());
    }

    @Test
    void orderMissing_throws_andDedupRecordRolledBack() {
        long orderNo = System.nanoTime();
        PaySuccessEvent e = event(orderNo);
        assertThatThrownBy(() -> paidService.handle(e)).isInstanceOf(OrderNotVisibleException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM consumed_event WHERE event_id = ?", Long.class, e.eventId())).isZero();

        // 订单出现后同一事件再来（模拟重投）：正常处理
        placeOrder(orderNo);
        assertThat(paidService.handle(e)).isTrue();
    }

    @Test
    void endToEnd_eventArrivesFirst_redeliveredAfterOrderCommitted_orderPaid() throws Exception {
        long orderNo = System.nanoTime();
        producer.send(new Message(PaySuccessEvent.TOPIC, PaySuccessEvent.TAG, objectMapper.writeValueAsBytes(event(orderNo))));

        // 等消费者至少处理过一次（失败、等待重投）再落单
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(countOrders(orderNo)).isZero();
        placeOrder(orderNo);

        // 第一次重投在 10 秒后（之后 30s、1m…）
        await().atMost(Duration.ofSeconds(90)).until(() ->
                jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", Integer.class, orderNo) == 20);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox WHERE biz_key = ? AND tag = 'ORDER_PAID'",
                Long.class, String.valueOf(orderNo))).isEqualTo(1);
    }
}
