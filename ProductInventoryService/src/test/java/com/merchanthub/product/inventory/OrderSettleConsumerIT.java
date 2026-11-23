package com.merchanthub.product.inventory;

import com.merchanthub.product.inventory.model.dto.ReserveRequest;
import com.merchanthub.product.inventory.mq.OrderSettledEvent;
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

/** 端到端：ORDER_EVENT 上的 ORDER_CLOSED / ORDER_PAID（各重复投递 3 次）→ 消费者 → 库存只改一次 */
@SpringBootTest(properties = "merchanthub.inventory.settle-consumer.enabled=true")
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
            // C1：和 order 发出的消息一样带上 tenantId，消费端据此恢复租户上下文
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
        assertThat(stock()).containsExactly(10, 0);   // 还了一次，不是三次（13）
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
}
