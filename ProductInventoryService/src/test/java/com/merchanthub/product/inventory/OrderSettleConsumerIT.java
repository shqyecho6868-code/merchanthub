package com.merchanthub.product.inventory;

import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.mq.OrderSettledEvent;
import com.merchanthub.product.inventory.mq.OrderSettleConsumerConfig;
import com.merchanthub.product.inventory.service.InventoryReservationService;
import com.merchanthub.common.tenant.mq.TenantMessages;
import com.merchanthub.product.ProductItSupport;
import com.merchanthub.testsupport.SharedContainers;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End to end: ORDER_CLOSED / ORDER_PAID on ORDER_EVENT (each delivered 3 times) → consumer → stock changes only once.
 * Max retries set to 0 (straight to DLQ on first failure) to verify the DLQ alert; kept in the same test class rather than a separate one:
 * different config = a different Spring context, and same-group consumers in two contexts split the queues, so the message may land on the one without the reduced retry count
 */
@SpringBootTest(properties = {
        "merchanthub.inventory.settle-consumer.enabled=true",
        "merchanthub.inventory.settle-consumer.max-reconsume-times=0"})
class OrderSettleConsumerIT extends ProductItSupport {

    private static final long TENANT = 7L;
    private static DefaultMQProducer producer;

    @Autowired
    private InventoryReservationService reserveService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void start() throws Exception {
        SharedContainers.rocketmq().createTopic(OrderSettledEvent.TOPIC);
        producer = new DefaultMQProducer("it-order-settle-producer");
        producer.setNamesrvAddr(SharedContainers.rocketmq().getNamesrvAddr());
        producer.setVipChannelEnabled(false);
        producer.start();
    }

    @AfterAll
    static void stop() {
        producer.shutdown();
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE TABLE inventory");
        jdbc.execute("TRUNCATE TABLE inventory_reservation");
        jdbc.update("INSERT INTO inventory (tenant_id, sku_id, available, reserved) VALUES (?, 101, 10, 0)", TENANT);
    }

    private int[] stock() {
        return jdbc.queryForObject("SELECT available, reserved FROM inventory WHERE sku_id = 101",
                (rs, i) -> new int[]{rs.getInt(1), rs.getInt(2)});
    }

    private void send3Times(String tag, long orderNo) throws Exception {
        String json = "{\"eventId\":" + System.nanoTime() + ",\"orderNo\":" + orderNo + ",\"tenantId\":" + TENANT
                + ",\"event\":\"" + (tag.equals(OrderSettledEvent.TAG_ORDER_PAID) ? "PAY_SUCCESS" : "PAY_TIMEOUT") + "\"}";
        for (int i = 0; i < 3; i++) {
            // Carry tenantId like messages sent by order do; the consumer restores the tenant context from it
            producer.send(TenantMessages.stamp(new Message(OrderSettledEvent.TOPIC, tag, json.getBytes(StandardCharsets.UTF_8)), TENANT));
        }
    }

    @Test
    void orderClosed_duplicated_releasedOnce() throws Exception {
        long orderNo = System.nanoTime();
        reserveService.reserve(new ReserveRequest(orderNo, TENANT, List.of(new ReserveRequest.Item(101L, 2))));
        send3Times(OrderSettledEvent.TAG_ORDER_CLOSED, orderNo);

        await().atMost(Duration.ofSeconds(60)).until(() -> stock()[1] == 0);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(stock()).containsExactly(10, 0);   // returned once, not three times (13)
    }

    @Test
    void orderPaid_duplicated_confirmedOnce() throws Exception {
        long orderNo = System.nanoTime();
        reserveService.reserve(new ReserveRequest(orderNo, TENANT, List.of(new ReserveRequest.Item(101L, 2))));
        send3Times(OrderSettledEvent.TAG_ORDER_PAID, orderNo);

        await().atMost(Duration.ofSeconds(60)).until(() -> stock()[1] == 0);
        await().pollDelay(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(stock()).containsExactly(8, 0);
    }

    /**
     * DLQ monitoring: inventory data is already inconsistent (the reservation record says 1 unit reserved, but the inventory row's reserved is 0), so release must fail and retrying won't help.
     * Conclusion: on the last failure, write recon_exception (DEAD_LETTER) + an ERROR alert; the message then goes to %DLQ%inventory-order-settle
     */
    @Test
    void unrecoverableFailure_lastAttempt_raisesDeadLetterAlarm() throws Exception {
        jdbc.execute("TRUNCATE TABLE recon_exception");
        long orderNo = System.nanoTime();
        jdbc.update("INSERT INTO inventory_reservation (tenant_id, order_no, sku_id, quantity, status) VALUES (?, ?, 101, 1, 10)",
                TENANT, orderNo);
        String json = "{\"eventId\":1,\"orderNo\":" + orderNo + ",\"tenantId\":" + TENANT + ",\"event\":\"PAY_TIMEOUT\"}";
        producer.send(TenantMessages.stamp(new Message(OrderSettledEvent.TOPIC, OrderSettledEvent.TAG_ORDER_CLOSED,
                json.getBytes(StandardCharsets.UTF_8)), TENANT));

        await().atMost(Duration.ofSeconds(60)).until(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM recon_exception WHERE type = 'DEAD_LETTER'", Long.class) == 1);
        assertThat(jdbc.queryForObject("SELECT biz_key FROM recon_exception", String.class))
                .startsWith(OrderSettleConsumerConfig.CONSUMER_GROUP + ":");
        // The whole transaction rolled back: the reservation record is still RESERVED, left for manual handling / reservation recon
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE order_no = ?", Integer.class, orderNo))
                .isEqualTo(10);
    }
}
